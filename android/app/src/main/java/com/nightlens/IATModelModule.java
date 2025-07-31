package com.nightlens;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactMethod;

public class IATModelModule extends ReactContextBaseJavaModule {

    public IATModelModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    public String getName() {
        return "IATModelModule"; // JS에서 NativeModules.IATModelModule로 접근할 이름
    }

    @ReactMethod
    public void initializeModel(Promise promise) {
        try {
            // 여기에 실제 모델 초기화 코드 작성
            promise.resolve("모델 초기화 성공!");
        } catch (Exception e) {
            promise.reject("INITIALIZATION_FAILED", e);
        }
    }
}
