/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.openejb.client;

import java.lang.reflect.Method;

import javax.ejb.RemoveException;

public class StatelessEJBHomeHandler extends EJBHomeHandler {

    public StatelessEJBHomeHandler() {
    }

    public StatelessEJBHomeHandler(EJBMetaDataImpl ejb, ServerMetaData server, ClientMetaData client, JNDIContext.AuthenticationInfo auth) {
        super(ejb, server, client, auth);
    }

    protected Object findX(Method method, Object[] args, Object proxy) throws Throwable {
        throw new SystemException(new UnsupportedOperationException("Session beans may not have find methods"));
    }

    protected Object removeByPrimaryKey(Method method, Object[] args, Object proxy) throws Throwable {
        throw new ApplicationException(new RemoveException("Session objects are private resources and do not have primary keys"));
    }

    protected Object removeWithHandle(Method method, Object[] args, Object proxy) throws Throwable {
        // you can't really remove a stateless handle
        return null;
    }

    protected EJBObjectHandler newEJBObjectHandler() {
        return new StatelessEJBObjectHandler();
    }

}
