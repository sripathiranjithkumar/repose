/*
 * _=_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=
 * Repose
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Copyright (C) 2010 - 2015 Rackspace US, Inc.
 * _-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_-_=_
 */
package features.services.datastore

import framework.ReposeConfigurationProvider
import framework.ReposeValveLauncher
import framework.ReposeValveTest
import framework.category.Slow
import org.junit.experimental.categories.Category
import org.openrepose.commons.utils.io.ObjectSerializer
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.PortFinder

@Category(Slow.class)
class RemoteDatastoreServiceTest extends ReposeValveTest {
    //Since we're serializing objects here for the Remote datastore, we must have the objects in our classpath
    final ObjectSerializer objectSerializer = new ObjectSerializer(this.getClass().getClassLoader())

    static def params
    static def remoteDatastoreEndpoint

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)
        int datastorePort = PortFinder.Singleton.getNextOpenPort()
        int reposePort2 = PortFinder.Singleton.getNextOpenPort()

        remoteDatastoreEndpoint = "http://localhost:${datastorePort}"

        params = properties.getDefaultTemplateParams()
        params += ['datastorePort': datastorePort]
        params += ['reposePort2': reposePort2]

        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/services/datastore/remote/clients", params)
        repose.start([clusterId: "repose", nodeId: "client1"])

        def repose2ConfigProvider = new ReposeConfigurationProvider(properties)
        def repose2 = new ReposeValveLauncher(repose2ConfigProvider, properties)
        repose2.enableDebug()
        //def repose2LogSearch = new ReposeLogSearch(logFile)
        repose2.configurationProvider.applyConfigs("common", params)
        repose2.configurationProvider.applyConfigs("features/services/datastore/remote/clients", params)
        repose2.start([clusterId: "repose", nodeId: "client2"])

        def datastoreConfigProvider = new ReposeConfigurationProvider(properties)
        def datastore = new ReposeValveLauncher(datastoreConfigProvider, properties)
        datastore.enableDebug()
        //def datastoreLogSearch = new ReposeLogSearch(logFile)
        datastore.configurationProvider.applyConfigs("common", params)
        datastore.configurationProvider.applyConfigs("features/services/datastore/remote/datastore", params)
        datastore.start([clusterId: "repose", nodeId: "remote"])

        waitUntilReadyToServiceRequests()
    }

    def "when configured with Remote Datastore service, repose should start and successfully execute calls"() {
    }

    def "PUT'ing a cache object should return 202"() {
    }

    def "PATCH'ing a new cache object should return 200 response"() {
    }

    def "PATCH'ing a cache object to an existing key should patch the cached value"() {
    }

    def "GET'ing a cache object when time to live is expired should return 404"() {
    }

    def "DELETE'ing a cache object should return 204"() {
    }

    def "TRACE'ing should return 405 response"() {
    }
}
