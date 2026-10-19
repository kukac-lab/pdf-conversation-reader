package uk.colinkuka.pdfvoice;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class DialogueTest {
    @Test public void preservesSpeakerAcrossWrappedLines(){
        List<Dialogue.Turn> t=Dialogue.parse("Alex: First line\ncontinued.\nSam: Reply.");
        assertEquals(2,t.size());assertEquals("Alex",t.get(0).speaker);
        assertEquals("First line continued.",t.get(0).text);assertEquals("Sam",t.get(1).speaker);
    }
    @Test public void supportsNarrationAndEmptyDocuments(){
        assertTrue(Dialogue.parse(" \n ").isEmpty());
        assertEquals("Narrator",Dialogue.parse("Opening narration.").get(0).speaker);
    }
    @Test public void longUnbrokenTextDoesNotLoseUnicode(){
        StringBuilder b=new StringBuilder();for(int i=0;i<400;i++)b.append("😀");
        List<Dialogue.Turn> t=Dialogue.parse("Alex: "+b);
        StringBuilder joined=new StringBuilder();for(Dialogue.Turn part:t){assertTrue(part.text.length()<=350);assertFalse(Character.isHighSurrogate(part.text.charAt(part.text.length()-1)));joined.append(part.text);}
        assertEquals(b.toString(),joined.toString());
    }
    @Test public void supportsUnicodeSpeakerNames(){
        assertEquals("Élodie",Dialogue.parse("Élodie: Bonjour.").get(0).speaker);
    }
}
