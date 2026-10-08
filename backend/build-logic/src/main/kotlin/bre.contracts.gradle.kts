// Convention plugin for :libs:bre-contracts — OpenAPI 3.1, .proto, AsyncAPI
// 3.0 and the event JSON Schemas (LLD §1.1).
//
// Code generation (protobuf, OpenAPI) and the buf breaking-change check land
// in M2, when there is a contract to generate from and a baseline to break
// against. For M0 this carries the pure-library conventions and holds the
// contract sources as resources so they are versioned and diffable from day
// one.

plugins {
    id("bre.java-library")
}

sourceSets {
    main {
        resources {
            srcDir("src/main/contracts")
        }
    }
}
