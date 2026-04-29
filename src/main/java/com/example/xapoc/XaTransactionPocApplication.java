package com.example.xapoc;

import com.atomikos.spring.AtomikosAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication(exclude = {AtomikosAutoConfiguration.class})
@EnableTransactionManagement
public class XaTransactionPocApplication {

    public static void main(String[] args) {
        SpringApplication.run(XaTransactionPocApplication.class, args);
    }
}
