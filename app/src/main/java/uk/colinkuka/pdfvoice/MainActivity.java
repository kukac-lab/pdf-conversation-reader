package uk.colinkuka.pdfvoice;

import android.app.*;
import android.os.*;
import android.content.*;
import android.net.Uri;
import android.graphics.Color;
import android.speech.tts.*;
import android.view.*;
import android.widget.*;
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    private TextToSpeech tts;
    private boolean ready, playing, loading;
    private int position, generation, loadGeneration;
    private float rate = 1f;
    private String appliedText = "";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private List<Dialogue.Turn> turns = new ArrayList<>();
    private final List<Voice> voices = new ArrayList<>();
    private final Map<String, Voice> assignments = new HashMap<>();
    private LinearLayout root, cast;
    private TextView status, current, progress, speed;
    private EditText editor;
    private Button play;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        PDFBoxResourceLoader.init(getApplicationContext());
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(22),dp(24),dp(22),dp(32)); root.setBackgroundColor(Color.rgb(244,246,243));
        scroll.addView(root); setContentView(scroll);
        label("PDF  /  AUDIO READER", 12);
        label("Bring the conversation\nto life.", 30);
        label("Open a PDF, check the dialogue and listen.", 16);
        button(root,"Open a PDF", v -> openPdf());
        button(root,"Try a sample conversation", v -> {
            if (loading) return;
            stop(); editor.setText("Alex: What is phishing?\nSam: It is a deceptive message that tries to make you share sensitive information or open a harmful link.\nAlex: What should I do if a message looks suspicious?\nSam: Verify the sender through a trusted channel before taking action."); prepare();
        });
        status = label("Choose a text-based PDF. Scanned images need OCR first.",14);
        label("1. Review the text",20);
        label("Speaker labels such as Alex: and Sam: select different voices. Correct PDF reading order here before listening.",14);
        editor = new EditText(this); editor.setGravity(Gravity.TOP); editor.setTextSize(16);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        editor.setHint("Alex: Hello!\nSam: Shall we begin?"); editor.setMinLines(6); editor.setMaxLines(10); root.addView(editor);
        button(root,"Apply text and set voices", v -> prepare());
        label("2. Choose voices",20);
        cast = new LinearLayout(this); cast.setOrientation(LinearLayout.VERTICAL); root.addView(cast);
        label("Only installed voices that report offline support are listed. Add voices in your phone's text-to-speech settings if needed.",13);
        button(root,"Open speech settings", v -> {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
        });
        label("3. Listen",20);
        current = label("Your dialogue will appear here.",20); current.setPadding(0,dp(16),0,dp(16));
        progress = label("No conversation loaded",14);
        LinearLayout controls = new LinearLayout(this); root.addView(controls);
        button(controls,"Previous",v -> move(-1));
        play = button(controls,"Play",v -> { if (playing) pause(); else start(); });
        button(controls,"Next",v -> move(1));
        button(root,"Restart",v -> { stop(); position=0; showCurrent(); });
        speed=label("Speed · 1.00×",15);
        SeekBar seek=new SeekBar(this); seek.setMax(150); seek.setProgress(50); root.addView(seek);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int n,boolean user){ rate=(n+50)/100f; speed.setText(String.format(Locale.UK,"Speed · %.2f×",rate)); }
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){ if(playing){pause();start();} }
        });
        label("Pause resumes from the start of the current short passage. Playback pauses when you leave this app. PDFs are processed on this device; no upload is performed.",13);
        tts = new TextToSpeech(this, code -> runOnUiThread(() -> {
            if(isDestroyed()) return;
            ready=code==TextToSpeech.SUCCESS;
            if(!ready){ status.setText("Speech engine unavailable. Install or enable a text-to-speech engine in Settings."); return; }
            refreshVoices();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                public void onStart(String id){}
                public void onDone(String id){ runOnUiThread(() -> { if(playing && id.equals(token())){position++; speak();} }); }
                public void onError(String id){ runOnUiThread(() -> { if(id.equals(token())){pause(); status.setText("This voice could not speak. Choose another installed voice or check speech settings.");} }); }
            });
        }));
    }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density);}
    private TextView label(String text,int size){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(Color.rgb(30,52,49));t.setPadding(0,dp(8),0,dp(8));root.addView(t);return t;}
    private Button button(LinearLayout parent,String text,View.OnClickListener click){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setMinHeight(dp(48));b.setOnClickListener(click);parent.addView(b,parent==root?new LinearLayout.LayoutParams(-1,-2):new LinearLayout.LayoutParams(0,-2,1));return b;}
    private void openPdf(){
        if(loading) return;
        pause(); Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("application/pdf");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,10);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=10 || result!=RESULT_OK || data==null || data.getData()==null) return;
        Uri uri=data.getData(); stop(); loading=true; editor.setEnabled(false);status.setText("Reading PDF on your device…");
        final int job=++loadGeneration;
        worker.execute(() -> {
            try {
                byte[] bytes;
                try(InputStream in=getContentResolver().openInputStream(uri); ByteArrayOutputStream out=new ByteArrayOutputStream()){
                    if(in==null) throw new IOException("Cannot open this file.");
                    byte[] buffer=new byte[8192]; int n,total=0;
                    while((n=in.read(buffer))!=-1){total+=n;if(total>20*1024*1024)throw new IOException("Please use a PDF smaller than 20 MB.");out.write(buffer,0,n);}
                    bytes=out.toByteArray();
                }
                String text;
                try(PDDocument pdf=PDDocument.load(bytes)){
                    if(!pdf.getCurrentAccessPermission().canExtractContent()) throw new IOException("This PDF does not permit text extraction.");
                    if(pdf.getNumberOfPages()>250) throw new IOException("Please split this PDF into files of 250 pages or fewer.");
                    PDFTextStripper stripper=new PDFTextStripper();stripper.setSortByPosition(true);text=stripper.getText(pdf);
                }
                if(text.length()>500000)throw new IOException("This PDF contains too much text. Split it into smaller files.");
                final String extracted=text;
                runOnUiThread(() -> {
                    if(isDestroyed()||job!=loadGeneration)return;
                    loading=false;editor.setEnabled(true);editor.setText(extracted);prepare();
                    status.setText(extracted.trim().isEmpty()?"No readable text found. This may be a scanned PDF; use OCR first.":"PDF loaded. Check the text and speaker labels before playing.");
                });
            } catch(Exception e){
                final String msg=e instanceof com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException?"Password-protected PDF. Open an unlocked copy.":"Could not read PDF. "+(e.getMessage()==null?"Try another file.":e.getMessage());
                runOnUiThread(() -> {if(!isDestroyed()&&job==loadGeneration){loading=false;editor.setEnabled(true);status.setText(msg);}});
            }
        });
    }
    private void refreshVoices(){
        if(!ready)return;
        voices.clear();Set<Voice> available=tts.getVoices();
        if(available!=null)for(Voice v:available)if(!v.isNetworkConnectionRequired())voices.add(v);
        voices.sort(Comparator.comparing(v -> v.getLocale().toLanguageTag()+v.getName()));
        assignments.clear();buildCast();
        if(voices.isEmpty())status.setText("No offline voices found. Download a voice in speech settings, then return here.");
    }
    private void prepare(){
        if(loading)return;
        stop(); appliedText=editor.getText().toString(); turns=Dialogue.parse(appliedText);position=0;assignments.clear();buildCast();showCurrent();
        status.setText(turns.isEmpty()?"Add some dialogue first.":"Ready. Choose a voice for each speaker, then press Play.");
    }
    private void buildCast(){
        cast.removeAllViews();LinkedHashSet<String> speakers=new LinkedHashSet<>();for(Dialogue.Turn t:turns)speakers.add(t.speaker);
        int ordinal=0;
        for(String name:speakers){
            TextView title=new TextView(this);title.setText(name);title.setTextSize(16);cast.addView(title);
            if(voices.isEmpty())continue;
            Spinner select=new Spinner(this);List<String> labels=new ArrayList<>();for(Voice v:voices)labels.add(v.getLocale().getDisplayName()+" · "+v.getName());
            ArrayAdapter<String> adapter=new ArrayAdapter<>(this,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);select.setAdapter(adapter);
            List<Integer> matching=new ArrayList<>();for(int i=0;i<voices.size();i++)if(voices.get(i).getLocale().getLanguage().equals(Locale.getDefault().getLanguage()))matching.add(i);
            int chosen=matching.isEmpty()?ordinal%voices.size():matching.get(ordinal%matching.size());ordinal++;
            assignments.put(name,voices.get(chosen));select.setSelection(chosen);cast.addView(select);
            select.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
                public void onItemSelected(android.widget.AdapterView<?> p,View v,int i,long id){assignments.put(name,voices.get(i));}
                public void onNothingSelected(android.widget.AdapterView<?> p){}
            });
        }
    }
    private String token(){return generation+":"+position;}
    private void start(){
        if(loading)return;
        if(!ready||voices.isEmpty()){status.setText("Choose an installed offline speech voice in Settings first.");return;}
        if(turns.isEmpty() || !appliedText.equals(editor.getText().toString()))prepare();if(turns.isEmpty())return;
        if(position>=turns.size())position=0;
        playing=true;generation++;play.setText("Pause");editor.setEnabled(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);speak();
    }
    private void speak(){
        if(!playing)return;
        if(position>=turns.size()){pause();progress.setText("Finished · "+turns.size()+" passages");return;}
        showCurrent();Dialogue.Turn turn=turns.get(position);Voice voice=assignments.get(turn.speaker);
        if(voice==null||tts.setVoice(voice)==TextToSpeech.ERROR){pause();status.setText("Please choose an available voice.");return;}
        tts.setSpeechRate(rate);
        if(tts.speak(turn.text,TextToSpeech.QUEUE_FLUSH,null,token())==TextToSpeech.ERROR){pause();status.setText("Unable to start speech. Try another voice.");}
    }
    private void showCurrent(){
        if(turns.isEmpty()){current.setText("Your dialogue will appear here.");progress.setText("No conversation loaded");return;}
        int i=Math.min(position,turns.size()-1);Dialogue.Turn t=turns.get(i);current.setText(t.speaker+"\n\n"+t.text);progress.setText("Passage "+(i+1)+" of "+turns.size());
    }
    private void pause(){playing=false;generation++;if(tts!=null)tts.stop();if(play!=null)play.setText("Play");if(editor!=null)editor.setEnabled(!loading);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private void stop(){pause();position=0;}
    private void move(int delta){if(turns.isEmpty()||loading)return;boolean resume=playing;pause();position=Math.max(0,Math.min(turns.size()-1,position+delta));showCurrent();if(resume)start();}
    @Override protected void onResume(){super.onResume();if(ready)refreshVoices();}
    @Override protected void onPause(){pause();super.onPause();}
    @Override protected void onDestroy(){loadGeneration++;worker.shutdownNow();if(tts!=null){tts.stop();tts.shutdown();}super.onDestroy();}
}
