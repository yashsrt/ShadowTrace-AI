import java.util.*;
public class ShadowTraceTests {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);System.out.println("PASS "+msg);}
    public static void main(String[] args)throws Exception{
        ShadowTraceAI.setup();
        ShadowTraceAI.Model m=ShadowTraceAI.MODELS.get("Database");
        var healthy=m.predict(new double[]{90,.4,32,99.98});
        var failure=m.predict(new double[]{360,5,83,99.2});
        check(healthy.ratio<1,"baseline classified normal");
        check(failure.ratio>=2,"major incident classified critical");
        check(failure.score>healthy.score,"novelty score increases for failure");
        boolean rejected=false;
        try{ShadowTraceAI.ingest("Database",90,-1,32,99.9,"test");}catch(IllegalArgumentException e){rejected=true;}
        check(rejected,"negative error rate rejected");
        ShadowTraceAI.ingest("Database",360,5,83,99.2,"test");
        ShadowTraceAI.ingest("PaymentAPI",240,3,60,99.8,"test");
        ShadowTraceAI.ingest("Checkout",200,2.5,40,99.9,"test");
        check(ShadowTraceAI.findRoot().root.equals("Database"),"database ranked as likely root");
        check(ShadowTraceAI.snapshot().contains("\"services\""),"JSON snapshot includes services");
        System.out.println("All tests passed.");
    }
}
