package com.miauto.app;

import android.app.Service;
import android.content.Intent;
import androidx.core.app.CoreComponentFactory;
import com.andrerinas.openheadunit.App;

/** Keeps the SDK's component name and Binder contract, while replacing its AP backend. */
public class MiAutoComponentFactory extends CoreComponentFactory {
    @Override public Service instantiateService(ClassLoader loader, String name, Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        App app = App.Companion.getInstance();
        if (CarLinkTransport.SDK_AP_SERVICE.equals(name) && app != null &&
                CarLinkTransport.LOCAL_HOTSPOT.equals(CarLinkTransport.get(app))) {
            return new PublicHotspotService();
        }
        return super.instantiateService(loader, name, intent);
    }
}
