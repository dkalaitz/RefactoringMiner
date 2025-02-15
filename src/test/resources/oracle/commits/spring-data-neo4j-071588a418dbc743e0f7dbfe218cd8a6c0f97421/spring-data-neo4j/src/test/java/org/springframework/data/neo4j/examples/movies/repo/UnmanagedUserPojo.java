/*
 * Copyright (c)  [2011-2015] "Pivotal Software, Inc." / "Neo Technology" / "Graph Aware Ltd."
 *
 * This product is licensed to you under the Apache License, Version 2.0 (the "License").
 * You may not use this product except in compliance with the License.
 *
 * This product may include a number of subcomponents with
 * separate copyright notices and licence terms.  Your use of the source
 * code for these subcomponents is subject to the terms and
 * conditions of the subcomponent's licence, as noted in the LICENSE file.
 */

package org.springframework.data.neo4j.examples.movies.repo;

import org.springframework.data.neo4j.annotation.QueryResult;

/**
 * Test query result object to prove the case for POJOs <b>NOT</b> scanned by the mapping metadata.
 */
@QueryResult
public class UnmanagedUserPojo {

    private String name;

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
