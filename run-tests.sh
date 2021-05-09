#!/bin/sh

if test "$1" = "run"
then
	docker run -e ACCEPT_EULA=Y -e SA_PASSWORD=Str0ngP4ssw0rd \
		-p 1433:1433 --name sql-server19 \
		-d mcr.microsoft.com/mssql/server:2019-GA-ubuntu-16.04
fi
if test "$1" = "rm"
then
	docker container rm sql-server19
fi
if test "$1" = "start"
then
	docker container start sql-server19
fi
if test "$1" = "stop"
then
	docker container stop sql-server19
fi
if test "$1" = "create"
then
	# assumes you already have a MySQL instance running locally
	NEXT_JDBC_TEST_MYSQL=yes clojure -X:test next.jdbc.test-fixtures/create-clojure-test
fi
if test "$1" = ""
then
	NEXT_JDBC_TEST_MSSQL=yes MSSQL_SA_PASSWORD=Str0ngP4ssw0rd \
		NEXT_JDBC_TEST_MYSQL=yes clojure -X:test:runner
fi
