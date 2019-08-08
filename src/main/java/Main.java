import org.apache.catalina.startup.Tomcat;

import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) throws Exception {
        Tomcat tomcat = new Tomcat();
        tomcat.start();
        TimeUnit.DAYS.sleep(1);
    }
}
