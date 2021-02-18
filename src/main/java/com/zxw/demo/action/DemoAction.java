package com.zxw.demo.action;

import com.zxw.demo.service.IDemoService;
import com.zxw.framework.annotation.Autowired;
import com.zxw.framework.annotation.Controller;
import com.zxw.framework.annotation.RequestMapping;
import com.zxw.framework.annotation.RequestParam;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

//虽然，用法一样，但是没有功能
@Controller
@RequestMapping("/demo")
public class DemoAction {
    
    @Autowired
    private IDemoService demoService;
    
    @RequestMapping("/query")
    public void query(HttpServletRequest req, HttpServletResponse resp, @RequestParam("name") String name) {
        //		String result = demoService.get(name);
        String result = "My name is " + name;
        try {
            resp.getWriter().write(result);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @RequestMapping("/add")
    public void add(HttpServletResponse resp, @RequestParam("a") Integer a, @RequestParam("b") Integer b,
            HttpServletRequest req) {
        try {
            resp.getWriter().write(a + "+" + b + "=" + (a + b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @RequestMapping("/sub")
    public void add(HttpServletRequest req, HttpServletResponse resp, @RequestParam("a") Double a,
            @RequestParam("b") Double b) {
        try {
            resp.getWriter().write(a + "-" + b + "=" + (a - b));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    @RequestMapping("/remove")
    public String remove(@RequestParam("id") Integer id) {
        return "" + id;
    }
    
}
