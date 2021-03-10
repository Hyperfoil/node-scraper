# Node-scraper

Alternative scraper for openshift-monitoring/node-exporter.

## Deployment

```bash
oc new-app quay.io/rvansa/node-scraper
oc create sa node-scraper
oc create clusterrole node-scraper --non-resource-url=/metrics --verb=get
oc adm policy add-cluster-role-to-user node-scraper -z node-scraper
oc patch deployment node-scraper -p '{"spec":{"template":{"spec":{"serviceAccountName":"node-scraper"}}}}'
```

## Usage

You can create the configuration file using

```bash
oc get node -l node-role.kubernetes.io/worker -o json | \
    jq -c '{ nodes: [ .items[] | { node : .metadata.name | split(".") | .[0], url: ("https://" + .status.addresses[0].address + ":9100/metrics") }] , scrapeInterval: 5000}'
```

```
curl -s node-scraper:8080/start -H 'content-type: application/json' --data-binary @/path/to/config.json > /tmp/node-scraper.job
... run the test ...
curl -s node-scraper:8080/stop/$(cat /tmp/node-scraper.job) > /path/to/cpu.stats.json
```

## Hyperfoil integration

Store the configuration into `node-scraper-config.json` and prepare these files:

> node-scraper-start.sh
```bash
#!/bin/bash
CWD=$(dirname $0)
curl -s node-scraper:8080/start -H 'content-type: application/json' --data-binary @$CWD/.node-scraper-config.json > $RUN_DIR/node-scraper.job
```

> node-scraper-stop.sh
```bash
#!/bin/bash
curl -s node-scraper:8080/stop/$(cat $RUN_DIR/node-scraper.job) > $RUN_DIR/cpu.json
jq -c -s '{ hyperfoil: .[0], cpu: { "$schema": "urn:node-scraper", data: .[1] }}' $RUN_DIR/all.json $RUN_DIR/cpu.json > $RUN_DIR/result.json
```

Then run
```
oc create cm node-scraper-start --from-file=99-node-scraper-start.sh=node-scraper-start.sh --from-file=.node-scraper-config.json=node-scraper-config.json
oc create cm node-scraper-stop --from-file=00-node-scraper-stop.sh=node-scraper-stop.sh
```

> It's important that the config file is created as a hidden file (starting with `.`), otherwise Hyperfoil would try to execute it and fail.

You can now `oc edit hf` and add these:

```yaml
# ...
spec:
  postHooks:
  - node-scraper-stop
  preHooks:
  - node-scraper-start
# ...
```