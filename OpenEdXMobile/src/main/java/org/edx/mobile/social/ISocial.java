package org.edx.mobile.social;

import android.app.Application.ActivityLifecycleCallbacks;
import android.content.Intent;
import android.support.annotation.Nullable;

import com.microsoft.identity.client.exception.MsalException;

import org.edx.mobile.logger.Logger;
import org.jetbrains.annotations.NotNull;

public interface ISocial extends ActivityLifecycleCallbacks {

    Logger logger = new Logger(ISocial.class.getName());

    void login();
    void logout();
    void setCallback(ISocial.Callback callback);
    void onActivityResult(int requestCode, int resultCode, @Nullable Intent data);

    interface Callback {
        void onLogin(String accessToken);

        default void onCancel() {
        }

        default void onError(@Nullable Exception exception) {
        }
    }
}
