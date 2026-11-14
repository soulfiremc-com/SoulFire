#!/bin/bash

if [ -n "$SF_PWD" ]; then
  cd $SF_PWD
fi

if [ -z "$SF_RAM" ]; then
  echo "SF_RAM is not set. Defaulting to 2G."
  SF_RAM="2G"
fi

if [ -z "$SF_JAR" ]; then
  echo "SF_JAR is not set. Using default jar file of this image."
  SF_JAR="/soulfire/soulfire.jar"
fi

echo "Starting Soulfire dedicated server..."
java -Xmx$SF_RAM $SF_JVM_FLAGS -XX:+EnableDynamicAgentLoading -XX:+UnlockExperimentalVMOptions -XX:+UseZGC -XX:+UseCompactObjectHeaders -XX:+AlwaysActAsServerClassMachine -XX:+UseNUMA -XX:+UseFastUnorderedTimeStamps -XX:+UseVectorCmov -XX:+UseCriticalJavaThreadPriority -Dsf.flags.v2=true -jar $SF_JAR
