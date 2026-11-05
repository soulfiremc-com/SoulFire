package com.soulfiremc.launcher;

import java.lang.reflect.Method;

// Too early to init loggers
@SuppressWarnings("CallToPrintStackTrace")
public abstract class SoulFireAbstractJava8Launcher {
  public void run(String[] args) {
    try {
      Class<?> launcherClass = Class.forName(getLauncherClassName());
      Method mainMethod = launcherClass.getDeclaredMethod("main", String[].class);
      mainMethod.setAccessible(true);
      mainMethod.invoke(launcherClass.newInstance(), (Object) args);
    } catch (UnsupportedClassVersionError e) {
      System.out.println("[Soulfire] Soulfire requires Java 25 or higher!");
      System.out.println("[Soulfire] Please update your Java version!");
      System.out.println("[Soulfire] You are currently using Java " + System.getProperty("java.version"));
      System.out.println("[Soulfire] You can download the latest version of Java at https://adoptopenjdk.net/");
    } catch (ClassNotFoundException e) {
      System.out.println("SoulFireLauncher is not in the classpath!");
      e.printStackTrace();
    } catch (ReflectiveOperationException e) {
      throw new RuntimeException(e);
    }
  }

  protected abstract String getLauncherClassName();
}
