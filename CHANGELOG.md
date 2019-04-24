# Change Log

* 2019-04-22 -- 1.0.0-alpha9 -- Fix #14 by respecting `:gen-fn` (as of 1.0.0-alpha10: `:builder-fn`) in `execute-one` for `PreparedStatement`.
* 2019-04-21 -- 1.0.0-alpha8 -- Initial publicly announced release.

## Unreleased Changes

The following changes have been committed to the **master** branch and will be in the next release:

* Fix #15 by adding `:next.jdbc/sql-string` to options hash map that is passed down into the builder function.
* Rename `:gen-fn` option to `:builder-fn` since the term "builder" is used everywhere.
