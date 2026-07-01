// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.starrocks.authentication;

import com.starrocks.persist.gson.RuntimeTypeAdapterFactory;
import com.starrocks.sql.analyzer.SemanticException;

import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * Loads optional SecurityIntegration implementations from fe-plugin-shield.jar.
 */
public final class PluginSecurityIntegrationSupport {
    public static final String SHIELD_SHARED_AUTH_TYPE = "authentication_shield_shared";
    private static final String SHIELD_SHARED_CLASS =
            "com.oppo.starrocks.shield.auth.ShieldSharedSecurityIntegration";

    private PluginSecurityIntegrationSupport() {
    }

    public static boolean isShieldSharedAuthType(String type) {
        return SHIELD_SHARED_AUTH_TYPE.equalsIgnoreCase(type);
    }

    public static SecurityIntegration createShieldShared(String name, Map<String, String> propertyMap) {
        try {
            Class<?> clazz = Class.forName(SHIELD_SHARED_CLASS);
            Constructor<?> ctor = clazz.getConstructor(String.class, Map.class);
            return (SecurityIntegration) ctor.newInstance(name, propertyMap);
        } catch (ClassNotFoundException e) {
            throw new SemanticException("fe-plugin-shield is not loaded, cannot create security integration type '"
                    + SHIELD_SHARED_AUTH_TYPE + "'");
        } catch (ReflectiveOperationException e) {
            throw new SemanticException("failed to create security integration '" + SHIELD_SHARED_AUTH_TYPE + "': "
                    + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    public static void registerShieldSharedSubtype(RuntimeTypeAdapterFactory<SecurityIntegration> factory) {
        try {
            Class<?> clazz = Class.forName(SHIELD_SHARED_CLASS);
            factory.registerSubtype((Class<? extends SecurityIntegration>) clazz, "ShieldSharedSecurityIntegration");
        } catch (ClassNotFoundException ignored) {
            // fe-plugin-shield.jar not on classpath
        }
    }

    public static void preparePropertiesForPersist(Map<String, String> propertyMap) {
        if (propertyMap == null || propertyMap.isEmpty()) {
            return;
        }
        String type = propertyMap.get(SecurityIntegration.SECURITY_INTEGRATION_PROPERTY_TYPE_KEY);
        boolean maybeShieldShared = type == null
                || isShieldSharedAuthType(type)
                || propertyMap.containsKey(ShieldSharedAuthPropertyKeys.PASSWORD)
                || propertyMap.containsKey(ShieldSharedAuthPropertyKeys.PASSWORD_HASH)
                || propertyMap.containsKey("shield.shared.password_file");
        if (!maybeShieldShared) {
            return;
        }
        try {
            Class<?> clazz = Class.forName("com.oppo.starrocks.shield.auth.ShieldSharedPropertyPreparer");
            clazz.getMethod("prepare", Map.class).invoke(null, propertyMap);
        } catch (ClassNotFoundException ignored) {
            // fe-plugin-shield.jar not on classpath
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new SemanticException("failed to prepare shield shared auth properties: " + cause.getMessage());
        }
    }
}
