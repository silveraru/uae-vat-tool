package ae.vattool;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class VatToolApplication {
    public static void main(String[] args) {
        SpringApplication.run(VatToolApplication.class, args);
    }
}
