package com.github.jikoo.enchantableblocks.util;

import java.io.File;
import java.lang.reflect.Field;
import org.bukkit.plugin.java.JavaPlugin;

public class PluginHelper {

    public static void setDataDir(JavaPlugin plugin) throws NoSuchFieldException, IllegalAccessException {
        Field field = JavaPlugin.class.getDeclaredField("dataFolder");
        field.setAccessible(true);
        // Looks gross, but works around rare path issues with separator char on various OSs in Java 8.
        File dataFolder = new File(new File(new File(new File(".", "src"), "test"), "resources"), plugin.getName());
        field.set(plugin, dataFolder);
        field = JavaPlugin.class.getDeclaredField("configFile");
        field.setAccessible(true);
        field.set(plugin, new File(dataFolder, "config.yml"));

        plugin.reloadConfig();
    }

    private PluginHelper() {}

}
