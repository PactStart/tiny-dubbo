package com.pactrex.serviceprovider;

import org.springframework.context.support.ClassPathXmlApplicationContext;

public class Bootstrap {

	@SuppressWarnings("resource")
	public static void main(String[] args) {
		new ClassPathXmlApplicationContext("spring.xml");
	}
}
