# Node-scraper

Alternative scraper for openshift-monitoring/node-exporter.

## Deployment

```bash
oc new-app quay.io/rvansa/node-scraper
oc create sa node-scraper                                                                    
oc create clusterrole node-scraper --non-resource-url=/metrics --verb=get
oc adm policy add-clusterrole-to-user node-scraper -z node-scraper
oc patch dc node-scraper -p '{"spec":{"template":{"spec":{"serviceAccountName":"node-scraper"}}}}'
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

