package com.zxw.framework.servlet.v1;

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

/**
 * 使用的设计模式   工厂模式  单例模式   委派模式   策略模式  模板模式
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
    private Map<String, Method> handlerMapping = new HashMap<String, Method>();
    
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
                String url = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");
                handlerMapping.put(url, method);
                System.out.println("Mapped :" + url + "," + method);
                
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
        //绝对路径
        String url = req.getRequestURI();
        //上下文地址
        String contextPath = req.getContextPath();
        //处理成相对路径
        url = url.replaceAll(contextPath, "").replaceAll("/+", "/");
        
        if (!this.handlerMapping.containsKey(url)) {
            resp.getWriter().write("404 Not Found!!!");
            return;
        }
        
        Method method = this.handlerMapping.get(url);
        
        //从reqest中拿到url传过来的参数
        Map<String, String[]> params = req.getParameterMap();
        //获取方法的形参列表
        Class<?>[] parameterTypes = method.getParameterTypes();
        
        //形参 和 传过来的参数对应
        Object[] paramValues = new Object[parameterTypes.length];
        for (int i = 0; i < parameterTypes.length; i++) {
            Class parameterType = parameterTypes[i];
            //不能用instanceof，parameterType它不是实参，而是形参
            if (parameterType == HttpServletRequest.class) {
                paramValues[i] = req;
                continue;
            } else if (parameterType == HttpServletResponse.class) {
                paramValues[i] = resp;
                continue;
            }
            Annotation[][] pa = method.getParameterAnnotations();
            for (int j = 0; j < pa.length; j++) {
                for (Annotation a : pa[j]) {
                    if (a instanceof RequestParam) {
                        // 拿到参数名称 eg:name
                        String paramName = ((RequestParam) a).value();
                        
                        //从req拿到参数表去找对应的key  eg: maps key:name value:wuhongyun
                        if (params.containsKey(paramName)) {
                            //一个key对应一个数组   一对多    而对方接受的是string
                            //数组转为string
                            String value = Arrays.toString(params.get(paramName)).replaceAll("\\[|\\]", "")
                                    .replaceAll("\\s", ",");
                            //类型的强制转化  url传过来的参数都是String类型的 parameterType:类型
                            
                            paramValues[i] = convert(parameterType, value);
                        }
                    }
                    
                }
                
            }
        }
        
        //投机取巧的方式
        //通过反射拿到method所在class，拿到class之后还是拿到class的名称
        //再调用toLowerFirstCase获得beanName
        String beanName = toLowerFirstCase(method.getDeclaringClass().getSimpleName());
        method.invoke(ioc.get(beanName), paramValues);
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
    
    
}