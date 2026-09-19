package com.kuro.visrelayfix;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;
import cpw.mods.fml.common.FMLLog;

import java.io.File;
import java.util.Map;

@IFMLLoadingPlugin.Name("Thaumcraft Vis Relay Fix")
@IFMLLoadingPlugin.MCVersion("1.7.10")
public final class VisRelayFixCorePlugin implements IFMLLoadingPlugin {
    @Override
    public String[] getASMTransformerClass() {
        return new String[]{VisRelayFixTransformer.class.getName()};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
        Object mcLocation = data.get("mcLocation");
        if (mcLocation instanceof File) {
            VisRelayFixConfig.initialize((File) mcLocation);
        }
        FMLLog.info("[VisRelayFix] Thaumcraft Vis Relay Fix 1.1.7 loaded.");
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
