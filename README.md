# Excavator

View and search your Docker container logs from STDOUT.

## Installation

1. Obtain an API key at https://neversleep.io . 

2. Run this on any Docker-capable host (Docker 1.8+ only):


    $ docker run -d -v /var/run/docker.sock:/var/run/docker.sock -e "API_KEY=your-api-key" neversleep/excavator


Copyright © 2015 NeverSleep

Distributed under the Eclipse Public License.

