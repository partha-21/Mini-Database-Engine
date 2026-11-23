#!/bin/bash
# Compiles the engine into ./out (needs JDK 17+)
set -e
mkdir -p out
javac -d out $(find src/minidb -name "*.java")
echo "Build OK."
echo "  java -cp out minidb.Main              # guided tour of every feature"
echo "  java -cp out minidb.Main repl [dir]   # interactive SQL shell (persistent)"
echo "  java -cp out minidb.Main bench [n]    # B+Tree vs LSM-Tree benchmark"
echo "  ./test.sh                             # run the test suite"
