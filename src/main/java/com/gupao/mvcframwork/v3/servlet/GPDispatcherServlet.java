package com.gupao.mvcframwork.v3.servlet;


import com.gupao.mvcframwork.annotation.*;

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
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GPDispatcherServlet extends HttpServlet {


    private Properties contextConfig = new Properties();
    private List<String> className = new ArrayList<>();
    private Map<String, Object> ioc = new HashMap<>();
//    private Map<String,Object> handlerMapping = new HashMap<>();


    private List<Handler> handlerMapping = new ArrayList<>();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        this.doPost(req, resp);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
//      6、调试运行
        try {
            this.doDispatch(req, resp);
        } catch (Exception e) {
            e.printStackTrace();
            resp.getWriter().write("500 Exection,Detail : " + Arrays.toString(e.getStackTrace()));
        }
    }

    private void doDispatch(HttpServletRequest req, HttpServletResponse resp) throws IOException, InvocationTargetException, IllegalAccessException {

        Handler handler = this.getHandler(req);
        if(handler == null){
            resp.getWriter().write("404 Not Fond!");
            return;
        }
//        获得方法的形参列表
        Class<?>[] paramTypes = handler.getParamTypes();

        Object[] paramValues = new Object[paramTypes.length];


        Map<String, String[]> params = req.getParameterMap();
//       循环参数列表，给方法中的形参赋值
        for (Map.Entry<String, String[]> parm:params.entrySet()) {

            String value = Arrays.toString(parm.getValue()).replaceAll("\\[|\\]","")
                    .replaceAll("\\s",",");

            if(!handler.paramIndexMapping.containsKey(parm.getKey())){continue; }

            int index = handler.getParamIndexMapping().get(parm.getKey());

            paramValues[index] = convert(paramTypes[index], value);
        }

//        处理request参数及response参数
        if(handler.getParamIndexMapping().containsKey(HttpServletRequest.class.getName())){
            Integer index = handler.getParamIndexMapping().get(HttpServletRequest.class.getName());
            paramValues[index] = req;

        }
        if(handler.getParamIndexMapping().containsKey(HttpServletResponse.class.getName())){
            Integer index = handler.getParamIndexMapping().get(HttpServletResponse.class.getName());
            paramValues[index] = resp;

        }

        Object returnValues = handler.method.invoke(handler.controller, paramValues);
        if(returnValues ==null || returnValues instanceof Void){
            return;
        }
        resp.getWriter().write(returnValues.toString());
    }


    //type conversion
    //只需要把String转换为任意类型就好
    private Object convert(Class<?> type,String value){
        //如果是int
        if(Integer.class == type){
            return Integer.valueOf(value);
        }
        else if(Double.class == type){
            return Double.valueOf(value);
        }
        //如果还有double或者其他类型，继续加if
        //这时候，我们应该想到策略模式了
        //在这里暂时不实现，希望小伙伴自己来实现
        return value;
    }

    /**
     * to find Handler from handlerMapping
     * @param request
     * @return
     */
    private Handler getHandler(HttpServletRequest request){

        if(handlerMapping==null){return null;};
        String uri = request.getRequestURI();
        String contextPath = request.getContextPath();
        uri = uri.replaceAll(contextPath,"").replaceAll("/+","/");
        for (Handler handler:handlerMapping) {
            Matcher matcher = handler.getPattern().matcher(uri);
            if(matcher.matches()){
                return handler;
            }
        }

        return null;

    }



    @Override
    public void init(ServletConfig config) throws ServletException {
//        1、加载配置文件
        doLoadConfig(config.getInitParameter("contextConfigLocation"));
//        2、扫描相关的类
        doScanner(contextConfig.getProperty("scanPackage"));
//        3、初始化扫描到的类，并把他们加载到IOC容器中
        doInstatnce();

//         4、完成依赖注入
        doAutowired();
//        5、初始化HandlerMapping
        initHandlerMapping();

        System.out.println("GP Spring framework is init.");
    }

    /**
     * 初始化URL和method之间的一一对应关系
     */
    private void initHandlerMapping() {
        if (ioc == null) return;


        for (Map.Entry entry : ioc.entrySet()) {
            Class clazz = entry.getValue().getClass();
            if (!clazz.isAnnotationPresent(GPController.class)) continue;

//            保存写在类上的@GPRequestMapping("/demo")

            String baseUrl = "";

            if (clazz.isAnnotationPresent(GPRequestMapping.class)) {
                GPRequestMapping annotation = (GPRequestMapping) clazz.getAnnotation(GPRequestMapping.class);

                baseUrl = annotation.value();
            }

//          默认获取所有public类
            for (Method method : clazz.getMethods()) {
                if (!method.isAnnotationPresent(GPRequestMapping.class)) continue;

                GPRequestMapping requestMapping = method.getAnnotation(GPRequestMapping.class);
                String regex = ("/" + baseUrl + "/" + requestMapping.value()).replaceAll("/+", "/");

                Pattern pattern = Pattern.compile(regex);
                this.handlerMapping.add(new Handler(pattern,entry.getValue(),method));
                System.out.println("Mapped" + regex + "," + method);

            }

        }

    }

    /**
     * 4、自动依赖注入
     */
    private void doAutowired() {

        if (ioc == null) return;
        for (Map.Entry entry : ioc.entrySet()) {
            Field[] fields = entry.getValue().getClass().getFields();
            for (Field field : fields) {

                if (!field.isAnnotationPresent(GPAutowired.class)) {
                    continue;
                }

                GPAutowired autowired = field.getAnnotation(GPAutowired.class);
                String beanName = autowired.value().trim();
                if ("".equals(beanName)) {
                    beanName = field.getType().getName();
                }

                field.setAccessible(true);

                try {
                    field.set(entry.getValue(), ioc.get(beanName));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            }


        }


    }

    /**
     * 3、初始化扫描到的类， 并把他们加载到IOC容器中
     */
    private void doInstatnce() {

        if (className == null) return;

        for (String path : className) {

            try {
                Class<?> clazz = Class.forName(path);


                //什么样的类才需要初始化呢？
                //加了注解的类，才初始化，怎么判断？
                //为了简化代码逻辑，主要体会设计思想，只举例 @Controller和@Service,
                // @Componment...就一一举例了

                if (clazz.isAnnotationPresent(GPController.class)) {
                    Object obj = clazz.newInstance();
                    String beanName = toLowerFirstCase(clazz.getName());
                    ioc.put(beanName, obj);
                } else if (clazz.isAnnotationPresent(GPService.class)) {
                    Object obj = clazz.newInstance();
//              1、自定义beanName
                    GPService service = clazz.getAnnotation(GPService.class);

                    String beanName = service.value();
//                    2、默认类名，首字母小写
                    if ("".equals(beanName.trim())) {
                        beanName = toLowerFirstCase(clazz.getName());
                    }
                    ioc.put(beanName, obj);
//                  3、根据类自动赋值
                    for (Class c : clazz.getInterfaces()) {

                        if (ioc.containsKey(c.getName())) {
                            throw new Exception("The “" + c.getName() + "” is exists!");
                        }

                        ioc.put(c.getName(), obj);
                    }
                } else {
                    continue;
                }


            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 扫描配置文件中的包
     *
     * @param scanPackage
     */
    private void doScanner(String scanPackage) {

        //scanPackage = com.gupaoedu.demo ，存储的是包路径
        //转换为文件路径，实际上就是把.替换为/就OK了
        //classpath
        URL url = this.getClass().getClassLoader().getResource("/" + scanPackage.replaceAll("\\.", "/"));

        File classPath = new File(url.getFile());

        for (File file : classPath.listFiles()) {
            if (file.isDirectory()) {
                this.doScanner(scanPackage + "." + file.getName());
            } else {
                if (!file.getName().endsWith(".class")) continue;
                String name = scanPackage + "." + file.getName().replaceAll(".class", "");
                className.add(name);
            }
        }


    }

    /**
     * 加载配置文件
     *
     * @param contextConfigLocation
     */
    private void doLoadConfig(String contextConfigLocation) {


        InputStream is = this.getClass().getClassLoader().getResourceAsStream(contextConfigLocation);


        try {
            contextConfig.load(is);
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }


    }

    /**
     * 将类名首字母转换成小写
     *
     * @param simpleName
     * @return
     */
    private String toLowerFirstCase(String simpleName) {

        char[] chars = simpleName.toCharArray();
        chars[0] += 32;
        return String.valueOf(chars);

    }


    public class Handler {

        private Pattern pattern;
        private Method method;
        private Object controller;
        private Class<?>[] paramTypes;

        private Map<String, Integer> paramIndexMapping;

        public Handler(Pattern pattern, Object controller, Method method) {
            this.pattern = pattern;
            this.method = method;
            this.controller = controller;

            paramTypes = method.getParameterTypes();

            paramIndexMapping = new HashMap<>();
            putParamIndexMapping(method);

        }

        private void putParamIndexMapping(Method method) {
            //提取方法中加了注解的参数
            //把方法上的注解拿到，得到的是一个二维数组
            //因为一个参数可以有多个注解，而一个方法又有多个参数
            Annotation[][] pa = method.getParameterAnnotations();
            for (int i = 0; i < pa.length; i++) {
                for (Annotation a : pa[i]) {

                    if (a instanceof GPRequestParam) {
                        String paraName = ((GPRequestParam) a).value();
                        if(!"".equals(paraName.trim())){
                            paramIndexMapping.put(paraName, i);
                        }
                    }
                }

            }

            //提取方法中的request和response参数
            Class<?>[] parameterTypes = method.getParameterTypes();
            for (int i = 0; i < parameterTypes.length; i++) {

                Class<?> parameterType = parameterTypes[i];
                if(parameterType == HttpServletRequest.class || parameterType == HttpServletResponse.class){

                    paramIndexMapping.put(parameterType.getName(),i);
                }

            }

        }

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

        public Map<String, Integer> getParamIndexMapping() {
            return paramIndexMapping;
        }
    }

}
