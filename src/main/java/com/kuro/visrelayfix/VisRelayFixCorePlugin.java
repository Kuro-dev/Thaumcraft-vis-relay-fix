package com.kuro.visrelayfix;

import cpw.mods.fml.relauncher.IFMLLoadingPlugin;

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
        // No launch-time configuration is required.
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
