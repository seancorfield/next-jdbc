#!/bin/sh

# start databases with: docker-compose up
# then:
#
# test against "all" databases with MySQL JDBC driver:
# ./run-tests.sh
#
# test against "all" databases with MariaDB JDBC driver:
# ./run-tests.sh maria

if test "$1" = "maria"
then
	NEXT_JDBC_TEST_MSSQL=yes MSSQL_SA_PASSWORD=Str0ngP4ssw0rd \
		NEXT_JDBC_TEST_MYSQL=yes NEXT_JDBC_TEST_MARIADB=yes clojure -X:test
fi
if test "$1" = ""
then
	NEXT_JDBC_TEST_MSSQL=yes MSSQL_SA_PASSWORD=Str0ngP4ssw0rd \
		NEXT_JDBC_TEST_MYSQL=yes clojure -X:test
fi
exit $?
