package com.harborpush;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.ServletComponentScan;

@SpringBootApplication
@ServletComponentScan
public class HarborPushApplication {

	public static void main(String[] args) {
		SpringApplication.run(HarborPushApplication.class, args);
	}

}
