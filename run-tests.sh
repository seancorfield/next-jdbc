#!/bin/sh

# start databases with: docker-compose up
# then: ./run-tests.sh create
# - creates a new database in MySQL for running tests
#
# test against "all" databases with MySQL JDBC driver:
# ./run-tests.sh
#
# test against "all" databases with MariaDB JDBC driver:
# ./run-tests.sh maria

if test "$1" = "create"
then
  sleep 30
	# assumes you already have a MySQL instance running locally
	NEXT_JDBC_TEST_MYSQL=yes clojure -X:test next.jdbc.test-fixtures/create-clojure-test
fi
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
