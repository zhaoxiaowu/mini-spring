package com.zxw.demo.service.impl;


import com.zxw.demo.service.IDemoService;
import com.zxw.framework.annotation.Service;

/**
 * 核心业务逻辑
 */
@Service
public class DemoService implements IDemoService {

	public String get(String name) {
		return "My name is " + name;
	}

}
