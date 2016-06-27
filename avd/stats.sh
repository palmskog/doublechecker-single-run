#!/bin/bash

DATE=`date +"%m-%d-%y"`
DCHECKER_DIR=$DATE"_doublechecker_stats"

if [[ $# -ne 1 ]]; then
    echo "Usage: sh <script_name> <local | remote>"
    exit 1
fi

DOEXP="doexp-"$1

cd /home/biswass

$DOEXP --project=avd --buildPrefix=FullAdaptive --workloadSize=small --config=AVDStats --bench=elevator,philo,hedc,sor,tsp,moldyn,montecarlo,raytracer,eclipse6,hsqldb6,lusearch9-fixed,sunflow9,xalan9,pmd9,luindex9,avrora9,jython9 --tasks=sync,build,exp --trial=10 --timeout=180 --retryTrials=true --baseName=$DCHECKER_DIR


