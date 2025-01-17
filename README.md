# jepsen.radix-dlt

Jepsen tests for the Radix distributed ledger system.

## Installation

In addition to a [Jepsen
environment](https://github.com/jepsen-io/jepsen#setting-up-a-jepsen-environment),
you'll need a RadixDLT server and client. Which client depends on which version
of Radix you're trying to test--see project.clj for several (commented-out)
versions that might be appropriate.

First, clone the RadixDLT repo:

```
git clone https://github.com/radixdlt/radixdlt.git
```

The most recent Radix build we're testing is:

```
feature/account-txn-log-write-read-consistency
```

And the client we're using with this build is:

```
feature/client-support-for-txn-accounting
```

### Building the Client

Check out whichever branch of the client you want to use, and compile and
install it to the local Maven repo. This apparently only builds with JDK 11, so
depending on your Gradle setup you might need to downgrade, compile, then flip
back.

```
cd radixdlt-engine
echo "apply plugin: 'maven'" >> build.gradle
../gradlew install

cd ../radixdlt-java-common/
echo "apply plugin: 'maven'" >> build.gradle
../gradlew install

cd ../radixdlt-java/radixdlt-java
echo "apply plugin: 'maven'" >> build.gradle
../../gradlew install
```

To see the exact version this installed, check:

```
ls ~/.m2/repository/com/radixdlt/radixdlt-java/
```

If you changed the version built, you'll also need to update `project.clj` to
refer to that particular version.

### Building the Server

If you need to build a custom build of Radix to test a patch:

```sh
cd radix
git checkout <SOME-VERSION>
DOCKER_BUILDKIT=1 docker build --output type=local,dest=out --progress plain -f radixdlt-core/docker/Dockerfile.build .
```

This will spit out a zipfile like

```
out/distributions/radixdlt-1.0.0-<branch>-SNAPSHOT.zip
```

Which you can copy to any local path you like, then run

```sh
lein run test ... --zip path/to/radixdlt-1.0.0-whatever-SNAPSHOT.zip
```

## Quickstart

To run the full test suite against a local cluster with nodes n1, n2, n3, n4,
and n5, build version `feature/account-txn-log-write-read-consistency`, then
run:

```
lein run test-all --zip txn-log-write-read-consistency.zip
```

To see the full list of CLI options, run

```
lein run test-all --help
```

The first ones you might want to tune are:

- `--nodes-file <file>`: A file with hostnames, one per line
- `--username <user>`: The username to use for logging in to DB nodes
- `--time-limit <seconds>`: How long to run the test for
- `--write-concurrency <n>`: How many processes should try to write?
- `--read-concurrency <n>`: How many processes should try to read?
- `--rate <hz>`: How many requests per second should we try for?
- `--test-count <number>`: How many tests to run
- `--nemesis <faults>`: Which faults to inject
- `--no-faithful`: Don't bother checking whether the transaction log faithfully represents submitted transactions.

## Browsing Results

Results are stored in `store/<name>/<timestamp>/`, and can be browsed with any
file browser. You can also launch a web server with:

```
lein run serve
```

... which will bind `http://localhost:8080`, offering a reverse chronological
list of all tests. You'll find a data structure describing analysis results,
including statistics, client errors, and any safety violations found, in
`results.edn`. `jepsen.log` has the full logs from the test run, and
`history.edn` and `history.txt` are machine and human-readable projections of
the history of logical operations Jepsen executed. Time-series plots of clock
skew, throughput, and latency can be found in `clock-skew.png`,
`latency-raw.png`, and `rate.png`. `test.fressian` is a binary representation
of the entire test: history, results, etc; this can be loaded and explored at
the REPL using `jepsen.repl` and `jepsen.store`.

The `accounts` directory has per-account visualizations. `n-balance.html` shows
the balance of an account over time. Time flows down; higher balances are drawn
to the right. Green boxes indicate balance reads, and blue boxes show the
resulting balances after executing transactions. Orange boxes show where a
balance couldn't be explained--for instance, after an intermediate read.

`n-timeline.html` shows a timeline of all operations involving that account:
color denotes whether the operation was `:ok` or `:info`. Time flows top to
bottom, and each process is shown as a distinct vertical track.

`n-txn-log.html` shows the pretty-printed longest transaction log for that account.

`n-txn-logs.html` shows all reads of an account in ~chronological order,
including the time of the read, the node it executed against, the function
(e.g. txn-log or raw-txn-log), and the txn IDs it observed. If a log diverges
from the longest "authoritative" log, its diverging entries are shown with
colorized backgrounds. Colors are hashes of the txn IDs, which makes it easy to
see insertions/deletions/etc.

`raw-txn` contains a sub-analysis specifically of raw-txn-log and raw-balances
operations.

Latency and throughput graphs can be a bit noisy, so `txn-perf` has dedicated
performance graphs of *just* txn operations.

## Testing Against Stokenet

To run tests against the public Stokenet, you'll need an address with XRD. Run
`lein run keygen` to construct a new account, and paste the results into
`stokenet.edn`; then fund that account with some XRD. Running tests with
`--stokenet` will use that account instead.

We don't have access to the raw txn APIs, so you'll need to run with

`--fs txn-log,balance`

## Passive Checks of Mainnet

This test suite also includes a passive checker which will perform read-only
queries against the Radix mainnet to identify two classes of consistency
anomalies: transactions which are present in one account's log but missing from
another, and transactions which are present in some log but in state `FAILED`.
To do this, check out the `1.0.0-compatible` branch, and run `lein pubcheck`:

```sh
git checkout 1.0.0-compatible
lein run pubcheck
```

This will take several hours. When it completes, run a second pass with

```sh
lein run pubcheck --recheck
```

... which will go back and find extra bugs. You can interrupt and resume these
checks at any time; its state is persistently journaled to /tmp/jepsen/cache.
This checker isn't particularly smart--it was a quick one-off and wasn't built
to last. When it's found an error, you can inspect it at the REPL with:

```sh
$ lein repl
(require '[jepsen.radix.pubcheck :as pc])
(->> (pc/load-state) :errors pprint)
```

## Project Structure

`project.clj` defines how to run this test suite, including our JVM
dependencies and entry point. Source code lives in `src/jepsen/radix-dlt/`.
Results of each test are stored in `store/`.

Key namespaces are:

`core.clj`: Entry point for the CLI. Parses arguments, constructs test maps,
and runs them.

`client.clj`: Wrapper library around the Radix Java client API, and also
utility methods which interact directly with some JSON-RPC APIs not exposed by
the Java client. Coerces between Clojure and the Radix client's representations
of datatypes. Coerces between various representations of account and validator
addresses, etc. Some common error handling.

`db.clj`: Installation, setup and teardown code for Radix. Also knows how to
join nodes to, and remove nodes from, the cluster. Defines database-related
fault injection

`nemesis.clj`: Fault injection. Glues together standard Jepsen nemeses like partitions, process crashes, pauses, and clock skew, together with custom nemeses like membership changes.

`workload.clj`: Generates operations for the main Radix test: transfer
transactions, balance reads, txn-log reads, and their raw counterparts. Defines
how to apply those operations to Radix nodes, and interprets their responses.

`accounts.clj`: Helps manage the workload's mapping of short numeric accounts
to private keys, etc.

`double_spend.clj`: An alternate workload which tries to pay two different
accounts from an account that can only pay one, and sees if both transactions
succeed.

`checker.clj`: Entry point for safety checkers and visualizations. Performs
isolation level anomaly detection over both the archive API and raw txn log.
Looks for negative balances. Checks to make sure that transactions are
faithfully represented in txn logs. Computes high-level aggregate statistics.
Renders per-account visualizations.

`checker/util.clj`: Analyzes history structure and constructs intermediate data
structures we use as a part of `checker.clj`.

`balance_vis.clj`: Renders the four visualizations of each account, including
balances over time, data and visual representations of the txn log(s), and
timelines of all operations over accounts.

`pubcheck.clj`: A standalone utility--not a full Jepsen test--which uses reads
of the public Radix mainnet to try to identify consistency anomalies in
production.

`util.clj`: Common utility functions.

## License

Copyright © 2021 Jepsen, LLC

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 2.0 which is available at
http://www.eclipse.org/legal/epl-2.0.

This Source Code may also be made available under the following Secondary
Licenses when the conditions for such availability set forth in the Eclipse
Public License, v. 2.0 are satisfied: GNU General Public License as published by
the Free Software Foundation, either version 2 of the License, or (at your
option) any later version, with the GNU Classpath Exception which is available
at https://www.gnu.org/software/classpath/license.html.
