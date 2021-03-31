package org.sunbird.kernel.extension.enums;

public enum HeaderParam {

    REQUEST_ID, REQUEST_PATH, REQUEST_ST_ED_PATH, CURRENT_INVOCATION_PATH, USER_DATA, USER_LOCALE, SYSTEM_LOCALE, USER_ID, PROXY_USER_ID,
    USER_NAME, PROXY_USER_NAME, SCOPE_ID, CONSUMER_ID, CHANNEL_ID, APP_ID, DEVICE_ID;

    public String getParamName() {
        return this.name();
    }

}
