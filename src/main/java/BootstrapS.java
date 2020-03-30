import org.apache.catalina.startup.Bootstrap;
import org.apache.catalina.startup.Tomcat;

import java.util.concurrent.TimeUnit;

/**
 * @author yangliu48
 */
public class BootstrapS {

    public static void main(String[] args) throws Exception {

        Tomcat tomcat = new Tomcat();
//        tomcat.init();
        tomcat.start();

        TimeUnit.DAYS.sleep(1);

    }


}
