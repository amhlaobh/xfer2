#!/bin/sh
set -o errexit
set -o nounset


# modify these:
project=xfer
compile=1
tmpclasses="tmp_classes" # set to "bin" if wished

# no modifications after here:

if [ ! -d src ] ; then
    echo "No src directory"
    exit 1
fi

[ "${OSTYPE}" == "cygwin" ] && isCygwin=1 || isCygwin=0
echo "Running in cygwin: ${isCygwin}"

today=`date +%Y%m%d`
jarfile=${project}-${today}.jar
srcfile=${project}-src-${today}.jar
bindir=bin
jarsdir=jars
exitAfterCompile=0
doClean=0
doJarClean=0
doDistClean=0
doArchive=1
if [ "$#" != 0 ] ; then
    if  [ "$1" == "-h" ] ; then
        echo "build.sh -c          compile only"
        echo "build.sh -clean      clean temporary files"
        echo "build.sh -jarclean  clean jars"
        echo "build.sh -distclean  clean everything but src files"
        echo "build.sh -noarchive  DON'T move built jar files to ${jarsdir}"
        exit 0
    fi
    if  [ "$1" == "-c" ] ; then
        exitAfterCompile=1
    fi
    if  [ "$1" == "-clean" ] ; then
        doClean=1
    fi
    if  [ "$1" == "-jarclean" ] ; then
        doJarClean=1
    fi
    if  [ "$1" == "-distclean" ] ; then
        doDistClean=1
    fi
    if  [ "$1" == "-noarchive" ] ; then
        doArchive=0
    fi
fi
here=`pwd`
cd $here

if [ "${doDistClean}" == "1" ] ; then
    doClean=1
    doJarClean=1
    echo "dist cleaning"
    echo "cleaning BUILD*"
    rm -rf BUILD-* BUILDNO
fi

if [ "${doClean}" == "1" ] ; then
    echo "cleaning dist"
    rm -rf dist
    echo "cleaning dist-src"
    rm -rf dist-src
    echo "cleaning ${tmpclasses}"
    rm -rf ${tmpclasses}
    echo "cleaning sources_list.txt"
    rm -rf  sources_list.txt
fi

if [ "${doJarClean}" == "1" ] ; then
    echo "cleaning jars"
    rm -rf ${project}-src*.jar 
    echo "cleaning source jars  "
    rm -rf ${project}-src*.jar 
fi

if [ "${doDistClean}" == "1" ] || [ "${doClean}" == "1" ] || [ "${doJarClean}" == "1" ] ; then
    exit 0
fi

# also compile, don't take class files from eclipse
echo "compile"
if [ "${compile}" == "1" ] ; then
    find ./src -name *.java > sources_list.txt
    if [ -d ${tmpclasses} ] ; then rm -rf ${tmpclasses} ; fi
    mkdir ${tmpclasses}
    javac -Xlint:deprecation -d ${tmpclasses} -g  @sources_list.txt

    bindir=${tmpclasses}

fi
if [ "${exitAfterCompile}" == "1" ] ; then
    echo "Compile only, exit."
    exit 0
fi

# determine build number
if [ ! -f BUILDNO ] ; then
    echo "1" > BUILDNO
    i=1
else
    i=`cat BUILDNO`
    if [ -f BUILD-${i} ] ; then
        rm BUILD-*
    fi
    (( i ++ ))
fi
echo $i > BUILDNO
touch BUILD-${i}


echo "Build $i"

echo packing bin
cd $here
if [ -d dist ] ; then rm -rf dist ; fi
mkdir dist
rsync -a ${bindir}/ dist
cp -p build.sh BUILDNO BUILD-* dist
cp -p manifest.mf dist
if [ -f Changelog ] ; then cp -p Changelog dist ; fi
cd dist
[ ${isCygwin} ] && \
    jar -cfm `cygpath -w ${here}/${jarfile} manifest.mf .` \
    || \
    jar -cfm ${here}/${jarfile} manifest.mf .

echo packing src
cd $here
if [ -d dist-src ] ; then rm -rf dist-src ; fi
mkdir dist-src
rsync -a src dist-src
for i in  doc/TODO doc/Changelog ; do if [ -f "${i}" ] ; then cp -p "${i}" dist-src ; fi ; done
cp -p manifest.mf build.sh dist-src
[ ${isCygwin} ] && \
    jar -cf `cygpath -w ${here}/${srcfile} dist-src` \
    ||\
    jar -cf ${here}/${srcfile} dist-src

if [ "${doArchive}" = "1" ] ; then
    if [ ! -d ${jarsdir} ] ; then mkdir ${jarsdir} ; fi
    mv ${here}/${jarfile}  ${here}/${jarsdir}
    mv ${here}/${srcfile} ${here}/${jarsdir}
fi
