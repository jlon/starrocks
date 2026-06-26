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

package com.starrocks.authorization;

import com.starrocks.common.DdlException;

import java.lang.reflect.Constructor;
import java.util.Map;

/**
 * Loads a custom {@link AccessController} implementation from an external plugin JAR
 * via catalog property {@code access.controller.class}.
 */
public final class AccessControllerLoader {
    public static final String ACCESS_CONTROLLER_CLASS_KEY = "access.controller.class";

    private AccessControllerLoader() {
    }

    public static AccessController load(String className, Map<String, String> properties) throws DdlException {
        try {
            Class<?> clazz = Class.forName(className);
            Constructor<?> constructor = clazz.getConstructor(Map.class);
            return (AccessController) constructor.newInstance(properties);
        } catch (ClassNotFoundException e) {
            throw new DdlException("Access controller class not found: " + className
                    + ". Please ensure the plugin JAR is placed in the FE lib directory.", e);
        } catch (NoSuchMethodException e) {
            throw new DdlException("Access controller class " + className
                    + " must provide a public constructor(Map<String, String> properties).", e);
        } catch (Exception e) {
            throw new DdlException("Failed to load access controller: " + className, e);
        }
    }
}
