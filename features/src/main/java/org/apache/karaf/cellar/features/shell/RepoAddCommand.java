/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.karaf.cellar.features.shell;

import org.apache.karaf.cellar.core.Configurations;
import org.apache.karaf.cellar.core.Group;
import org.apache.karaf.cellar.core.control.SwitchStatus;
import org.apache.karaf.cellar.core.event.EventProducer;
import org.apache.karaf.cellar.core.shell.CellarCommandSupport;
import org.apache.karaf.cellar.core.shell.completer.AllGroupsCompleter;
import org.apache.karaf.cellar.features.Constants;
import org.apache.karaf.cellar.features.FeatureState;
import org.apache.karaf.cellar.features.ClusterRepositoryEvent;
import org.apache.karaf.features.Feature;
import org.apache.karaf.features.FeaturesService;
import org.apache.karaf.features.Repository;
import org.apache.karaf.features.RepositoryEvent;
import org.apache.karaf.features.command.completers.AvailableRepoNameCompleter;
import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.Option;
import org.apache.karaf.shell.api.action.lifecycle.Reference;
import org.apache.karaf.shell.api.action.lifecycle.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Command(scope = "cluster", name = "feature-repo-add", description = "Add a features repository to a cluster group")
@Service
public class RepoAddCommand extends CellarCommandSupport {

    @Argument(index = 0, name = "group", description = "The cluster group name", required = true, multiValued = false)
    @Completion(AllGroupsCompleter.class)
    String groupName;

    @Argument(index = 1, name = "name/url", description = "Shortcut name of the features repository or the full URL", required = true, multiValued = false)
    @Completion(AvailableRepoNameCompleter.class)
    String nameOrUrl;

    @Argument(index = 2, name = "version", description = "The version of the features repository if using features repository name as first argument. It should be empty if using the URL.", required = false, multiValued = false)
    String version;

    @Option(name = "-i", aliases = {"--install"}, description = "Install all features contained in the features repository", required = false, multiValued = false)
    boolean install;

    @Reference
    private EventProducer eventProducer;

    @Reference
    private FeaturesService featuresService;

    @Override
    protected Object doExecute() throws Exception {
        // check if the group exists
        Group group = groupManager.findGroupByName(groupName);
        if (group == null) {
            System.err.println("Cluster group " + groupName + " doesn't exist");
            return null;
        }

        // check if the event producer is ON
        if (eventProducer.getSwitch().getStatus().equals(SwitchStatus.OFF)) {
            System.err.println("Cluster event producer is OFF");
            return null;
        }

        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            // get the features repositories in the cluster group
            Map<String, String> clusterRepositories = clusterManager.getMap(Constants.REPOSITORIES_MAP + Configurations.SEPARATOR + groupName);
            // get the features in the cluster group
            Map<String, FeatureState> clusterFeatures = clusterManager.getMap(Constants.FEATURES_MAP + Configurations.SEPARATOR + groupName);

            URI uri = featuresService.getRepositoryUriFor(nameOrUrl, version);
            if (uri == null) {
                uri = new URI(nameOrUrl);
            }
            // check if the URL is already registered
            String name = null;
            for (String repository : clusterRepositories.keySet()) {
                if (repository.equals(uri)) {
                    name = clusterRepositories.get(uri);
                    break;
                }
            }
            if (name == null) {
                // update the repository temporary locally
                Repository repository = null;
                boolean localRegistered = false;
                // local lookup
                for (Repository registeredRepository : featuresService.listRepositories()) {
                    if (registeredRepository.getURI().equals(uri)) {
                        repository = registeredRepository;
                        break;
                    }
                }
                if (repository == null) {
                    // registered locally
                    try {
                        featuresService.addRepository(uri);
                    } catch (Exception e) {
                        System.err.println("Repository URL " + uri + " is not valid: " + e.getMessage());
                        return null;
                    }
                    // get the repository
                    for (Repository registeredRepository : featuresService.listRepositories()) {
                        if (registeredRepository.getURI().equals(uri)) {
                            repository = registeredRepository;
                            break;
                        }
                    }
                } else {
                    localRegistered = true;
                }

                // update the features repositories in the cluster group
                clusterRepositories.put(uri.toString(), repository.getName());

                // update the features in the cluster group
                for (Feature feature : repository.getFeatures()) {
                    FeatureState featureState = new FeatureState();
                    featureState.setName(feature.getName());
                    featureState.setVersion(feature.getVersion());
                    featureState.setInstalled(featuresService.isInstalled(feature));
                    clusterFeatures.put(feature.getName() + "/" + feature.getVersion(), featureState);
                }

                // un-register the repository if it's not local registered
                if (!localRegistered)
                    featuresService.removeRepository(uri);

                // broadcast the cluster event
                ClusterRepositoryEvent event = new ClusterRepositoryEvent(uri.toString(), RepositoryEvent.EventType.RepositoryAdded);
                event.setInstall(install);
                event.setSourceGroup(group);
                eventProducer.produce(event);
            } else {
                System.err.println("Features repository URL " + uri + " already registered");
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalClassLoader);
        }

        return null;
    }

    public EventProducer getEventProducer() {
        return eventProducer;
    }

    public void setEventProducer(EventProducer eventProducer) {
        this.eventProducer = eventProducer;
    }

    public FeaturesService getFeaturesService() {
        return featuresService;
    }

    public void setFeaturesService(FeaturesService featuresService) {
        this.featuresService = featuresService;
    }
}
