用简洁的代码提炼Spring的基本设计思想，并实现SpringMVC的基本功能，完成web开发

## 实现思路

1. ![配置阶段](https://gitee.com/tostringcc/blog/raw/master/2020/24958106-372005336e681d15.png)

## 思考

### Spring的Bean是哪里来的？Bean是线程安全的吗？

初始化，通过扫描，利用反射new出来的

并且缓存在IOC容器中

Spring并没有对Bean做任何处理

 Bean是不是线程安全 取决于Bean的本身

### Spring Bean是怎么被回收的？

spring中的生命周期有比如：**singleton，prototype，session，request…**
Spring 中的**Bean默认是singleton**

**singleton**（全局的）是随着spring的存亡而存亡
GC回收原则，当bean的引用没有指向任何地方的时候，它就会被回收
spring中的singleton 存在于ioc 中，本身就是单例，是基于spring的上下文的，当spring本身不消失，自然ioc容器也不会消失，自然ioc容器中的引用也会一直被持有，那么自然spring中的bean也就不会被回收会一直存在

**prototype** 又叫多例模式，用的时候就new一下，用完就没有了。
**session** 存在这一次会话 session 中，session没有过期它就一直存在，session过期后它就没了。
**request** 存在这一次请求中，请求结束了它就结束。

### Github

https://github.com/zhaoxiaowu/mini-spring.git
