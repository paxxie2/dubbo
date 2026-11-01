/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the 'License'); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an 'AS IS' BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.dubbo.rpc.protocol.tri.rest.mapping

import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathCondition
import org.apache.dubbo.rpc.protocol.tri.rest.mapping.condition.PathExpression
import org.apache.dubbo.rpc.protocol.tri.rest.util.PathUtils

import spock.lang.Specification

class RadixTreeTest extends Specification {

    def "match"() {
        given:
            def tree = new RadixTree<String>()
            tree.addPath('/a/*', 'abc')
            tree.addPath('/a/{x}/d/e', 'bcd')
            tree.addPath('/a/{v:.*}/e', 'bcd')
        when:
            def match = tree.match('/a/b/d/e')
        then:
            !match.empty
    }

    def "question marks survive mapping and filter registration"() {
        given:
            def mappingTree = new RadixTree<String>()
            def filterTree = new RadixTree<String>()
            new PathCondition('/api', pattern).expressions.each { mappingTree.addPath(it, 'handler') }
            filterTree.addPath('/api/' + pattern, 'handler')
            def path = PathUtils.normalize('/api' + requestUri)
        expect:
            mappingTree.match(path)*.value == expected
            filterTree.match(path)*.value == expected
        where:
            pattern                     | requestUri                    | expected
            'pages/t?st.html'            | '/pages/test.html'            | ['handler']
            'pages/t?st.html'            | '/pages/test.html?lang=en#top' | ['handler']
            'pages/t?st.html'            | '/pages/t'                    | []
            'pages/t?st.html'            | '/pages/tst.html'             | []
            'pages/t?st.html'            | '/pages/teest.html'           | []
            'orders/{id:\\d+(?:-\\d+)?}' | '/orders/12'                  | ['handler']
            'orders/{id:\\d+(?:-\\d+)?}' | '/orders/12-3?expand=true'     | ['handler']
            'orders/{id:\\d+(?:-\\d+)?}' | '/orders/abc'                 | []
            'orders/{id:\\d+(?:-\\d+)?}' | '/orders/12-'                 | []
    }

    def "question-mark regular expressions retain path variables"() {
        given:
            def expression = new PathCondition('', '/orders/{id:\\d+(?:-\\d+)?}').expressions[0]
        expect:
            expression.match('/orders/12') == [id: '12']
            expression.match('/orders/12-3') == [id: '12-3']
    }

    def "clear"() {
        given:
            def tree = new RadixTree<String>()
            tree.addPath('/a/*', 'abc')
            tree.addPath('/a/{x}/d/e', 'bcd')
            tree.addPath('/a/{v:.*}/e', 'bcd')
        when:
            tree.remove(s -> s in ['abc', 'bcd'])
        then:
            tree.empty
    }

    def "test end match"() {
        given:
            def tree = new RadixTree<Boolean>()
            tree.addPath('/a/*/*', true)
        expect:
            tree.match(path).size() == len
        where:
            path       | len
            '/a'       | 0
            '/a/b'     | 0
            '/a/b/c'   | 1
            '/a/b/c/d' | 0
    }

    def "test repeat add,no predicate function"() {
        given:
            def tree = new RadixTree<Boolean>()
            Boolean val1 = tree.addPath('/a/*/*', false)
            Boolean val2 = tree.addPath('/a/*/*', true)
        expect:
            val1 == null;
            val2 == false;
    }

    def "test repeat add,use predicate function"() {
        given:
            def tree = new RadixTree<Boolean>()
            Boolean val1 = tree.addPath(PathExpression.parse('/a/*/*'), false, { a, b -> a == b })
            Boolean val2 = tree.addPath(PathExpression.parse('/a/*/*'), true, { a, b -> a == b })
            Boolean val3 = tree.addPath(PathExpression.parse('/a/*/*'), true, { a, b -> a == b })

        expect:
            val1 == null;
            val2 == null;
        val3 == true;
    }

    def "test repeat add,use predicate function and Registration"() {
        given:
            def tree = new RadixTree<Boolean>()
            Boolean val1 = tree.addPath(PathExpression.parse('/a/*/*'), false, { a, b -> a == b })
            Boolean val2 = tree.addPath(PathExpression.parse('/a/*/*'), true, { a, b -> a == b })
            Boolean val3 = tree.addPath(PathExpression.parse('/a/*/*'), true, { a, b -> a == b })

        expect:
            val1 == null;
            val2 == null;
            val3 == true;
    }

    def "test sub path match"() {
        given:
            def tree = new RadixTree<String>();
            tree.addPath("/update/{ruleId}", "a")
            tree.addPath("/update/{ruleId}/state", "b")
        expect:
            tree.match(path).get(0).value == result
        where:
            path                    | result
            '/update/1222222'       | 'a'
            '/update/1222222/state' | 'b'
    }
}
