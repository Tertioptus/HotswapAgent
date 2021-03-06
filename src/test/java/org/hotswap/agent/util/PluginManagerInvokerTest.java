package org.hotswap.agent.util;

import org.hotswap.agent.PluginManager;
import org.hotswap.agent.PluginRegistry;
import org.hotswap.agent.javassist.ClassPool;
import org.hotswap.agent.javassist.CtClass;
import org.hotswap.agent.javassist.CtNewMethod;
import org.hotswap.agent.plugin.hibernate.HibernatePlugin;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;

/**
 * Created by bubnik on 4.11.13.
 */
public class PluginManagerInvokerTest {

    @Test
    public void testBuildCallPluginMethod() throws Exception {
        HibernatePlugin plugin = new HibernatePlugin();
        registerPlugin(plugin);
//        plugin.init(PluginManager.getInstance());

        String s = PluginManagerInvoker.buildCallPluginMethod(plugin.getClass(),
                "hibernateInitialized",
                "\"Version\"", "java.lang.String",
                "Boolean.TRUE", "java.lang.Boolean");

        ClassPool classPool = ClassPool.getDefault();
        classPool.appendSystemPath();

        CtClass clazz = classPool.makeClass("Test");
        clazz.addMethod(CtNewMethod.make("public void test() {" + s + "}", clazz));
        Class testClass = clazz.toClass();


        Method testMethod = testClass.getDeclaredMethod("test");
        testMethod.invoke(testClass.newInstance());


    }

    // plugin registration is not public, use reflection to insert test data
    private void registerPlugin(Object plugin) throws NoSuchFieldException, IllegalAccessException {
        Field f = PluginRegistry.class.getDeclaredField("registeredPlugins");
        f.setAccessible(true);
        // noinspection unchecked
        Map<Class, Map<ClassLoader, Object>> registeredPlugins =
                (Map<Class, Map<ClassLoader, Object>>) f.get(PluginManager.getInstance().getPluginRegistry());
        registeredPlugins.put(plugin.getClass(), Collections.singletonMap(getClass().getClassLoader(), plugin));
    }
}
