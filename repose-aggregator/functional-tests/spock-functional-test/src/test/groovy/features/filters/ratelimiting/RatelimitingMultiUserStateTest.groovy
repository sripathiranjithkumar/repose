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
package features.filters.ratelimiting

import framework.ReposeValveTest
import groovy.json.JsonSlurper
import org.rackspace.deproxy.Deproxy
import org.rackspace.deproxy.MessageChain
import spock.lang.Unroll

class RatelimitingMultiUserStateTest extends ReposeValveTest {
    final Map<String, String> userHeaderDefault = ["X-PP-User": "user"]
    final Map<String, String> groupHeaderDefault = ["X-PP-Groups": "customer"]
    final Map<String, String> acceptHeaderJson = ["Accept": "application/json"]

    def setupSpec() {
        deproxy = new Deproxy()
        deproxy.addEndpoint(properties.targetPort)

        def params = properties.getDefaultTemplateParams()
        repose.configurationProvider.applyConfigs("common", params)
        repose.configurationProvider.applyConfigs("features/filters/ratelimiting/checkratelimit", params)
        repose.start()
    }

    def cleanupSpec() {
        if (repose)
            repose.stop()
        if (deproxy)
            deproxy.shutdown()
    }

    def cleanup() {
        waitForLimitReset()
    }

    //2233
    @Unroll("Check absolute and remaining limit for each limit group #limitgroup and #user")
    def "Check absolute limit on json"() {
        when: "the user send request to get rate limit with endpoint doesn't match with limit group"
        MessageChain mc1 = deproxy.makeRequest(url: reposeEndpoint + "/service2/limits", method: "GET",
                headers: userHeaderDefault + limitgroup + acceptHeaderJson);
        def jsonbody = mc1.receivedResponse.body
        def json = JsonSlurper.newInstance().parseText(jsonbody)

        then:
        mc1.handlings.size() == 1
        checkAbsoluteLimitJsonResponse(json, checklimit)

        where:
        limitgroup                          | user                         | checklimit
        ["X-PP-Groups": "query-limits"]     | ["X-PP-User": "querylimits"] | querylimit
        //The state after this point, using this limit group somehow breaks everything else in this limit, same problem
        ["X-PP-Groups": "customer"]         | ["X-PP-User": "customer"]    | customerlimit
        ["X-PP-Groups": "higher"]           | ["X-PP-User": "test"]        | highlimit
        ["X-PP-Groups": "reset-limits"]     | ["X-PP-User": "reset123"]    | resetlimit
        ["X-PP-Groups": "unique"]           | ["X-PP-User": "user1"]       | uniquelimit
        ["X-PP-Groups": "multiregex"]       | ["X-PP-User": "multiregex"]  | multiregexlimit
        ["X-PP-Groups": "all-limits"]       | ["X-PP-User": "all"]         | alllimit
        ["X-PP-Groups": "all-limits-small"] | ["X-PP-User": "allsmall"]    | allsmalllimit
        ["X-PP-Groups": "multi-limits"]     | ["X-PP-User": "multilimits"] | multilimit
        ["X-PP-Groups": "unlimited"]        | ["X-PP-User": "unlimited"]   | unlimitedlimit
        //This one should also pass, but it's being affected by a state bug: REP-2233
        ["X-PP-Groups": "user"]             | ["X-PP-User": "default"]     | defaultlimit
    }

    private int parseAbsoluteLimitFromJSON(String body, int limit) {
        def json = JsonSlurper.newInstance().parseText(body)
        return json.limits.rate[limit].limit[0].value
    }

    //using this for now
    private int parseRemainingFromJSON(String body, int limit) {
        def json = JsonSlurper.newInstance().parseText(body)
        return json.limits.rate[limit].limit[0].remaining
    }

    private String getDefaultLimits(Map group = null) {
        def groupHeader = (group != null) ? group : groupHeaderDefault
        MessageChain messageChain = deproxy.makeRequest(url: reposeEndpoint + "/service2/limits", method: "GET",
                headers: userHeaderDefault + groupHeader + acceptHeaderJson);

        return messageChain.receivedResponse.body
    }

    private void waitForLimitReset(Map group = null) {
        while (parseRemainingFromJSON(getDefaultLimits(group), 0) != parseAbsoluteLimitFromJSON(getDefaultLimits(group), 0)) {
            sleep(1000)
        }
    }

    //Just doing the assertions provides a much better output from spock
    static boolean checkAbsoluteLimitJsonResponse(Map json, List checklimit) {

        def listnode = json.limits.rate["limit"].flatten()
        //Have to massage away the "next-available" from the listnode list
        listnode = listnode.collect { entry ->
            entry.remove("next-available")
            entry
        }
        println("LISTNODE:   ${listnode}")
        println("CHECKLIMIT: ${checklimit}")

        //Subtract the required checks from the results on repose
        // If the list is empty, then we checked *everything* and didn't get any other limits back
        // If it's nonempty, we got other limits back that we didn't check for
        def onlyAllChecksFound = listnode - checklimit
        assert onlyAllChecksFound.size() == 0

        //Subtract the result from repose from our checks
        // If the result is an empty list, then all the checks were found!
        def allChecksFound = checklimit - listnode
        assert allChecksFound.size() == 0

        return true
    }

    // Describe the limits from limitgroups in the config
    //The ordering on these is totally bogus. There's no way this can work reliably :(
    final static List<Map> customerlimit = [
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'DAY', 'remaining': 5, 'verb': 'DELETE', 'value': 5],
            ['unit': 'MINUTE', 'remaining': 1000, 'verb': 'GET', 'value': 1000],
            ['unit': 'HOUR', 'remaining': 10, 'verb': 'POST', 'value': 10],
            ['unit': 'DAY', 'remaining': 5, 'verb': 'PUT', 'value': 5],
    ]

    final static List<Map> highlimit = [
            ['unit': 'HOUR', 'remaining': 100, 'verb': 'POST', 'value': 100],
            ['unit': 'MINUTE', 'remaining': 30, 'verb': 'GET', 'value': 30],
            ['unit': 'DAY', 'remaining': 50, 'verb': 'PUT', 'value': 50],
            ['unit': 'DAY', 'remaining': 50, 'verb': 'DELETE', 'value': 50]
    ]

    final static List<Map> resetlimit = [
            ['unit': 'MINUTE', 'remaining': 1000, 'verb': 'GET', 'value': 1000],
            ['unit': 'DAY', 'remaining': 5, 'verb': 'PUT', 'value': 5],
            ['unit': 'SECOND', 'remaining': 5, 'verb': 'GET', 'value': 5]
    ]

    final static List<Map> uniquelimit = [
            ['unit': 'HOUR', 'remaining': 100, 'verb': 'POST', 'value': 100],
            ['unit': 'MINUTE', 'remaining': 30, 'verb': 'GET', 'value': 30],
            ['unit': 'DAY', 'remaining': 50, 'verb': 'PUT', 'value': 50],
            ['unit': 'DAY', 'remaining': 50, 'verb': 'DELETE', 'value': 50]
    ]

    // Re ordering to match the config, even though that's not the order repose might return it in, it's better for
    // my sanity
    final static List<Map> multiregexlimit = [
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'POST', 'value': 3],
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'HOUR', 'remaining': 100, 'verb': 'POST', 'value': 100],
            ['unit': 'DAY', 'remaining': 50, 'verb': 'PUT', 'value': 50],
            ['unit': 'DAY', 'remaining': 50, 'verb': 'DELETE', 'value': 50]
    ]

    final static List<Map> alllimit = [
            ['unit': 'HOUR', 'remaining': 50, 'verb': 'ALL', 'value': 50]
    ]

    final static List<Map> allsmalllimit = [
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'ALL', 'value': 3]
    ]
    final static List<Map> multilimit = [
            ['unit': 'HOUR', 'remaining': 1, 'verb': 'GET', 'value': 1],
            ['unit': 'HOUR', 'remaining': 1, 'verb': 'POST', 'value': 1]
    ]
    final static List<Map> querylimit = [
            ['unit': 'HOUR', 'remaining': 0, 'verb': 'GET', 'value': 1],
    ]

    //The limits for unlimited should be EMPTY, no limits for this guy
    final static List<Map> unlimitedlimit = []

    final static List<Map> defaultlimit = [
            ['unit': 'MINUTE', 'remaining': 1000, 'verb': 'GET', 'value': 1000],
            ['unit': 'MINUTE', 'remaining': 3, 'verb': 'GET', 'value': 3],
            ['unit': 'HOUR', 'remaining': 10, 'verb': 'POST', 'value': 10],
            ['unit': 'DAY', 'remaining': 5, 'verb': 'PUT', 'value': 5],
            ['unit': 'DAY', 'remaining': 5, 'verb': 'DELETE', 'value': 5]
    ]
}
