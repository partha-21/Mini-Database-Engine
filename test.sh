#!/bin/bash
# Builds the engine and runs the dependency-free test suite
set -e
./build.sh > /dev/null
mkdir -p out-test
javac -d out-test -cp out src/test/java/minidb/Tests.java
java -cp out:out-test minidb.Tests
