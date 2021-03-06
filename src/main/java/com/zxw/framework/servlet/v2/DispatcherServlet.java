package com.zxw.framework.servlet.v2;

import com.zxw.framework.annotation.*;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 优化handMapping不支持正则，解决反射调用前需重新获取BeanName
 *
 * @author wuhongyun
 * @date 2020/11/21 12:28
 */
public class DispatcherServlet extends HttpServlet {
    
    
    //保存application.properties配置文件中的内容
    private Properties contextConfig = new Properties();
    
    //传说中的IOC容器，我们来揭开它的神秘面纱
    //为了简化程序，暂时不考虑ConcurrentHashMap
    // 主要还是关注设计思想和原理
    private Map<String, Object> ioc = new HashMap<String, Object>();
    
    //保存url和Method的对应关系
    //思考：为什么不用Map
    //你用Map的话，key，只能是url
    //Handler 本身的功能就是把url和method对应关系，已经具备了Map的功能
    //根据设计原则：冗余的感觉了，单一职责，最少知道原则，帮助我们更好的理解
    private List<HandlerMapping> handlerMapping = new ArrayList<HandlerMapping>();
    
    //保存扫描的所有的类名
    private List<String> classNames = new ArrayList<String>();
    
    /**
     * 初始化Bean 初始化映射关系
     * <p>1.加载配置文件
     * <p>2.初始化IOC容器
     * <p>3.扫描相关的类
     * <p>5.利用反射，创建实例并保存到容器
     * <p>6.扫描容器 进行DI操作 给没赋值的赋值
     * <p>7.URL和Method对应 HandlerMapping
     */
    @Override
    public void init(ServletConfig config) throws ServletException {
        // 加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
        //扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
        
        //初始化扫描到的类，并且将它们放入到ICO容器之中
        doInstance();
        
        //完成依赖注入
        doAutowired();
        
        //初始化HandlerMapping
        initHandlerMapping();
        
        System.out.println("Mini Spring framework is init.");
    }
    
    
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }
    
    /**
     * 调用，运行阶段
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        
        try {
            doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exection,Detail : " + Arrays.toString(e.getStackTrace()));
        }
    }
    
    /**
     * url和handler的关系
     */
    private void initHandlerMapping() {
        if (ioc.isEmpty()) {
            return;
        }
        
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            Class<?> clazz = entry.getValue().getClass();
            
            if (!clazz.isAnnotationPresent(Controller.class)) {
                continue;
            }
            
            //保存写在类上面的@RequestMapping("/demo")
            String baseUrl = "";
            if (clazz.isAnnotationPresent(RequestMapping.class)) {
                RequestMapping requestMapping = clazz.getAnnotation(RequestMapping.class);
                baseUrl = requestMapping.value();
            }
            
            //默认获取所有的public方法
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(RequestMapping.class)) {
                    continue;
                }
                
                RequestMapping requestMapping = method.getAnnotation(RequestMapping.class);
                //优化
                // //demo///query
                String regex = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                Pattern pattern = Pattern.compile(regex);
                this.handlerMapping.add(new HandlerMapping(pattern, entry.getValue(), method));
                System.out.println("Mapped :" + pattern + "," + method);
                
            }
        }
    }
    
    /**
     * DI 注入
     */
    private void doAutowired() {
        if (ioc.isEmpty()) {
            return;
        }
        
        for (Map.Entry<String, Object> entry : ioc.entrySet()) {
            //Declared 所有的，特定的 字段，包括private/protected/default
            //正常来说，普通的OOP编程只能拿到public的属性
            Field[] fields = entry.getValue().getClass().getDeclaredFields();
            for (Field field : fields) {
                if (!field.isAnnotationPresent(Autowired.class)) {
                    continue;
                }
                Autowired autowired = field.getAnnotation(Autowired.class);
                
                //如果用户没有自定义beanName，默认就根据类型注入
                //这个地方省去了对类名首字母小写的情况的判断，这个作为课后作业
                //小伙伴们自己去完善
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) {
                    //获得接口的类型，作为key待会拿这个key到ioc容器中去取值
                    beanName = field.getType().getName();
                }
                
                //如果是public以外的修饰符，只要加了@Autowired注解，都要强制赋值
                //反射中叫做暴力访问， 强吻
                field.setAccessible(true);
                
                try {
                    //用反射机制，动态给字段赋值
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
        }
    }
    
    /**
     * 通过反射 在IOC容器 初始化bean
     */
    private void doInstance() {
        //初始化，为DI做准备
        if (classNames.isEmpty()) {
            return;
        }
        
        try {
            for (String className : classNames) {
                Class<?> clazz = Class.forName(className);
                
                //什么样的类才需要初始化呢？
                //加了注解的类，才初始化，怎么判断？
                //为了简化代码逻辑，主要体会设计思想，只举例 @Controller和@Service,
                // @Componment...就一一举例了
                if (clazz.isAnnotationPresent(Controller.class)) {
                    Object instance = clazz.newInstance();
                    //Spring默认类名首字母小写
                    String beanName = toLowerFirstCase(clazz.getSimpleName());
                    ioc.put(beanName, instance);
                } else if (clazz.isAnnotationPresent(Service.class)) {
                    //service不但要实例化本身 还有接口
                    //1、自定义的beanName
                    Service service = clazz.getAnnotation(Service.class);
                    String beanName = service.value();
                    //2、默认类名首字母小写
                    if ("".equals(beanName.trim())) {
                        beanName = toLowerFirstCase(clazz.getSimpleName());
                    }
                    
                    Object instance = clazz.newInstance();
                    ioc.put(beanName, instance);
                    //3、根据类型自动赋值,投机取巧的方式
                    for (Class<?> i : clazz.getInterfaces()) {
                        if (ioc.containsKey(i.getName())) {
                            throw new Exception("The “" + i.getName() + "” is exists!!");
                        }
                        //把接口的类型直接当成key了
                        ioc.put(i.getName(), instance);
                    }
                } else {
                    continue;
                }
                
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    /**
     * 首字母小写
     */
    private String toLowerFirstCase(String simpleName) {
        char[] chars = simpleName.toCharArray();
        //之所以加，是因为大小写字母的ASCII码相差32，
        // 而且大写字母的ASCII码要小于小写字母的ASCII码
        //在Java中，对char做算学运算，实际上就是对ASCII码做算学运算
        chars[0] += 32;
        return String.valueOf(chars);
    }
    
    /**
     * 扫描相关的类,获取类名
     *
     * @param scanPackage 扫描的包路径
     */
    private void doScanner(String scanPackage) {
        //扫描的地址  包路径转化为文件路径
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));
        File classPath = new File(url.getFile());
        for (File file : classPath.listFiles()) {
            if (file.isDirectory()) {
                doScanner(scanPackage + "." + file.getName());
            } else {
                if (!file.getName().endsWith(".class")) {
                    continue;
                }
                String className = (scanPackage + "." + file.getName().replace(".class", ""));
                classNames.add(className);
            }
        }
    }
    
    /**
     * 加载配置文件
     *
     * @param contextConfigLocation 配置类位置
     */
    private void doLoadConfig(String contextConfigLocation) {
        try (InputStream fis = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);) {
            contextConfig.load(fis);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws Exception {
        HandlerMapping handler = getHandler(req);
        if (handler == null) {
            resp.getWriter().write("404 Not Found!!!");
            return;
        }
        
        //从reqest中拿到url传过来的参数
        Map<String, String[]> urlParams = req.getParameterMap();
        
        //获取方法的形参列表
        Class<?>[] methodParamTypes = handler.getParamTypes();
        
        //形参 和 传过来的参数对应
        Object[] paramValues = new Object[methodParamTypes.length];
        
        for (Map.Entry<String, String[]> parm : urlParams.entrySet()) {
            //值是数组 需要去掉[]
            String value = Arrays.toString(parm.getValue()).replaceAll("\\[|\\]", "").replaceAll("\\s", ",");
            
            if (!handler.paramIndexMapping.containsKey(parm.getKey())) {
                continue;
            }
            
            int index = handler.paramIndexMapping.get(parm.getKey());
            paramValues[index] = convert(methodParamTypes[index], value);
        }
        
        if (handler.paramIndexMapping.containsKey(HttpServletRequest.class.getName())) {
            int reqIndex = handler.paramIndexMapping.get(HttpServletRequest.class.getName());
            paramValues[reqIndex] = req;
        }
        
        if (handler.paramIndexMapping.containsKey(HttpServletResponse.class.getName())) {
            int respIndex = handler.paramIndexMapping.get(HttpServletResponse.class.getName());
            paramValues[respIndex] = resp;
        }
        
        Object returnValue = handler.method.invoke(handler.controller, paramValues);
        if (returnValue == null || returnValue instanceof Void) {
            return;
        }
        resp.getWriter().write(returnValue.toString());
        
    }
    
    private HandlerMapping getHandler(HttpServletRequest req) {
        if (handlerMapping.isEmpty()) {
            return null;
        }
        //绝对路径
        String url = req.getRequestURI();
        //上下文地址
        String contextPath = req.getContextPath();
        //处理成相对路径
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        
        for (HandlerMapping handler : this.handlerMapping) {
            Matcher matcher = handler.getPattern().matcher(url);
            if (!matcher.matches()) {
                continue;
            }
            return handler;
        }
        return null;
    }
    
    /**
     * url传过来的参数都是String类型的，HTTP是基于字符串协议 只需要把String转换为任意类型就好
     */
    private Object convert(Class<?> type, String value) {
        //如果是int
        if (Integer.class == type) {
            return Integer.valueOf(value);
        } else if (Double.class == type) {
            return Double.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        //这时候，我们应该想到策略模式了
        return value;
    }
    
    
    /**
     * 保存一个url和一个Method的关系
     */
    public class HandlerMapping {
        
        //必须把url放到HandlerMapping才好理解吧
        private Pattern pattern;  //正则
        
        private Method method;
        
        private Object controller;
        
        private Class<?>[] paramTypes;
        
        public Pattern getPattern() {
            return pattern;
        }
        
        public Method getMethod() {
            return method;
        }
        
        public Object getController() {
            return controller;
        }
        
        public Class<?>[] getParamTypes() {
            return paramTypes;
        }
        
        //形参列表
        //参数的名字作为key,参数的顺序，位置作为值
        private Map<String, Integer> paramIndexMapping;
        
        public HandlerMapping(Pattern pattern, Object controller, Method method) {
            this.pattern = pattern;
            this.method = method;
            this.controller = controller;
            
            paramTypes = method.getParameterTypes();
            
            paramIndexMapping = new HashMap<String, Integer>();
            putParamIndexMapping(method);
        }
        
        private void putParamIndexMapping(Method method) {
            
            //提取方法中加了注解的参数
            //把方法上的注解拿到，得到的是一个二维数组
            //因为一个参数可以有多个注解，而一个方法又有多个参数
            Annotation[][] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i++) {
                for (Annotation a : pa[i]) {
                    if (a instanceof RequestParam) {
                        String paramName = ((RequestParam) a).value();
                        if (!"".equals(paramName.trim())) {
                            paramIndexMapping.put(paramName, i);
                        }
                    }
                }
            }
            
            //提取方法中的request和response参数
            Class<?>[] paramsTypes = method.getParameterTypes();
            for (int i = 0; i < paramsTypes.length; i++) {
                Class<?> type = paramsTypes[i];
                if (type == HttpServletRequest.class || type == HttpServletResponse.class) {
                    paramIndexMapping.put(type.getName(), i);
                }
            }
            
        }
    }
    
}