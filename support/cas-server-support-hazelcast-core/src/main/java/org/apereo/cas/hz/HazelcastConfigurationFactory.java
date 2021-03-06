package org.apereo.cas.hz;

import com.hazelcast.aws.AwsDiscoveryStrategyFactory;
import com.hazelcast.azure.AzureDiscoveryStrategyFactory;
import com.hazelcast.config.Config;
import com.hazelcast.config.DiscoveryConfig;
import com.hazelcast.config.DiscoveryStrategyConfig;
import com.hazelcast.config.EvictionPolicy;
import com.hazelcast.config.JoinConfig;
import com.hazelcast.config.MapConfig;
import com.hazelcast.config.MaxSizeConfig;
import com.hazelcast.config.MulticastConfig;
import com.hazelcast.config.NetworkConfig;
import com.hazelcast.config.PartitionGroupConfig;
import com.hazelcast.config.TcpIpConfig;
import com.hazelcast.jclouds.JCloudsDiscoveryStrategyFactory;
import org.apache.commons.lang3.BooleanUtils;
import org.apereo.cas.configuration.model.support.hazelcast.BaseHazelcastProperties;
import org.apereo.cas.configuration.model.support.hazelcast.HazelcastClusterProperties;
import org.apereo.cas.configuration.model.support.hazelcast.discovery.HazelcastAwsDiscoveryProperties;
import org.apereo.cas.configuration.model.support.hazelcast.discovery.HazelcastAzureDiscoveryProperties;
import org.apereo.cas.configuration.model.support.hazelcast.discovery.HazelcastJCloudsDiscoveryProperties;
import org.apereo.cas.util.CollectionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * This is {@link HazelcastConfigurationFactory}.
 *
 * @author Misagh Moayyed
 * @since 5.2.0
 */
public class HazelcastConfigurationFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(HazelcastConfigurationFactory.class);

    /**
     * Build map config map config.
     *
     * @param hz             the hz
     * @param mapName        the storage name
     * @param timeoutSeconds the timeoutSeconds
     * @return the map config
     */
    public MapConfig buildMapConfig(final BaseHazelcastProperties hz, final String mapName, final long timeoutSeconds) {
        final HazelcastClusterProperties cluster = hz.getCluster();
        final EvictionPolicy evictionPolicy = EvictionPolicy.valueOf(cluster.getEvictionPolicy());

        LOGGER.debug("Creating Hazelcast map configuration for [{}] with idle timeoutSeconds [{}] second(s)", mapName, timeoutSeconds);

        final MaxSizeConfig maxSizeConfig = new MaxSizeConfig()
            .setMaxSizePolicy(MaxSizeConfig.MaxSizePolicy.valueOf(cluster.getMaxSizePolicy()))
            .setSize(cluster.getMaxHeapSizePercentage());

        return new MapConfig()
            .setName(mapName)
            .setMaxIdleSeconds((int) timeoutSeconds)
            .setBackupCount(cluster.getBackupCount())
            .setAsyncBackupCount(cluster.getAsyncBackupCount())
            .setEvictionPolicy(evictionPolicy)
            .setMaxSizeConfig(maxSizeConfig);
    }

    /**
     * Build config.
     *
     * @param hz         the hz
     * @param mapConfigs the map configs
     * @return the config
     */
    public Config build(final BaseHazelcastProperties hz, final Map<String, MapConfig> mapConfigs) {
        final Config cfg = build(hz);
        cfg.setMapConfigs(mapConfigs);
        return finalizeConfig(cfg, hz);
    }

    /**
     * Build config.
     *
     * @param hz        the hz
     * @param mapConfig the map config
     * @return the config
     */
    public Config build(final BaseHazelcastProperties hz, final MapConfig mapConfig) {
        final Map<String, MapConfig> cfg = new HashMap<>();
        cfg.put(mapConfig.getName(), mapConfig);
        return build(hz, cfg);
    }

    /**
     * Build config.
     *
     * @param hz the hz
     * @return the config
     */
    public Config build(final BaseHazelcastProperties hz) {
        final HazelcastClusterProperties cluster = hz.getCluster();
        final Config config = new Config();

        final JoinConfig joinConfig = cluster.getDiscovery().isEnabled()
            ? createDiscoveryJoinConfig(config, hz.getCluster()) : createDefaultJoinConfig(config, hz.getCluster());

        LOGGER.debug("Created Hazelcast join configuration [{}]", joinConfig);

        final NetworkConfig networkConfig = new NetworkConfig()
            .setPort(cluster.getPort())
            .setPortAutoIncrement(cluster.isPortAutoIncrement())
            .setJoin(joinConfig);

        LOGGER.debug("Created Hazelcast network configuration [{}]", networkConfig);
        config.setNetworkConfig(networkConfig);

        return config.setInstanceName(cluster.getInstanceName())
            .setProperty(BaseHazelcastProperties.HAZELCAST_DISCOVERY_ENABLED, BooleanUtils.toStringTrueFalse(cluster.getDiscovery().isEnabled()))
            .setProperty(BaseHazelcastProperties.IPV4_STACK_PROP, String.valueOf(cluster.isIpv4Enabled()))
            .setProperty(BaseHazelcastProperties.LOGGING_TYPE_PROP, cluster.getLoggingType())
            .setProperty(BaseHazelcastProperties.MAX_HEARTBEAT_SECONDS_PROP, String.valueOf(cluster.getMaxNoHeartbeatSeconds()));
    }

    private JoinConfig createDiscoveryJoinConfig(final Config config, final HazelcastClusterProperties cluster) {
        final HazelcastAwsDiscoveryProperties aws = cluster.getDiscovery().getAws();
        final HazelcastJCloudsDiscoveryProperties jclouds = cluster.getDiscovery().getJclouds();
        final HazelcastAzureDiscoveryProperties azure = cluster.getDiscovery().getAzure();
        
        final JoinConfig joinConfig = new JoinConfig();

        LOGGER.debug("Disabling multicast and TCP/IP configuration for discovery");
        joinConfig.getMulticastConfig().setEnabled(false);
        joinConfig.getTcpIpConfig().setEnabled(false);

        final DiscoveryConfig discoveryConfig = new DiscoveryConfig();
        final DiscoveryStrategyConfig strategyConfig;
        if (StringUtils.hasText(aws.getAccessKey()) && StringUtils.hasText(aws.getSecretKey()) && StringUtils.hasText(aws.getIamRole())) {
            LOGGER.debug("Creating discovery strategy based on AWS");
            strategyConfig = getDiscoveryStrategyConfigByAws(cluster);
        } else if (StringUtils.hasText(jclouds.getCredential()) && StringUtils.hasText(jclouds.getIdentity()) && StringUtils.hasText(jclouds.getProvider())) {
            LOGGER.debug("Creating discovery strategy based on Apache jclouds");
            strategyConfig = getDiscoveryStrategyConfigByJClouds(cluster);
        } else if (StringUtils.hasText(azure.getClientId()) && StringUtils.hasText(azure.getClientSecret()) && StringUtils.hasText(azure.getClusterId())) {
            LOGGER.debug("Creating discovery strategy based on Microsoft Azure");
            strategyConfig = getDiscoveryStrategyConfigByAzure(cluster);
        } else {
            throw new IllegalArgumentException("Could not create discovery strategy configuration. No discovery provider is defined in the settings");
        }

        LOGGER.debug("Creating discovery strategy configuration as [{}]", strategyConfig);
        discoveryConfig.setDiscoveryStrategyConfigs(CollectionUtils.wrap(strategyConfig));
        joinConfig.setDiscoveryConfig(discoveryConfig);
        return joinConfig;
    }

    private JoinConfig createDefaultJoinConfig(final Config config, final HazelcastClusterProperties cluster) {
        final TcpIpConfig tcpIpConfig = new TcpIpConfig()
            .setEnabled(cluster.isTcpipEnabled())
            .setMembers(cluster.getMembers())
            .setConnectionTimeoutSeconds(cluster.getTimeout());
        LOGGER.debug("Created Hazelcast TCP/IP configuration [{}] for members [{}]", tcpIpConfig, cluster.getMembers());

        final MulticastConfig multicastConfig = new MulticastConfig().setEnabled(cluster.isMulticastEnabled());
        if (cluster.isMulticastEnabled()) {
            LOGGER.debug("Created Hazelcast Multicast configuration [{}]", multicastConfig);
            multicastConfig.setMulticastGroup(cluster.getMulticastGroup());
            multicastConfig.setMulticastPort(cluster.getMulticastPort());

            final Set<String> trustedInterfaces = StringUtils.commaDelimitedListToSet(cluster.getMulticastTrustedInterfaces());
            if (!trustedInterfaces.isEmpty()) {
                multicastConfig.setTrustedInterfaces(trustedInterfaces);
            }
            multicastConfig.setMulticastTimeoutSeconds(cluster.getMulticastTimeout());
            multicastConfig.setMulticastTimeToLive(cluster.getMulticastTimeToLive());
        } else {
            LOGGER.debug("Skipped Hazelcast Multicast configuration since feature is disabled");
        }

        return new JoinConfig()
            .setMulticastConfig(multicastConfig)
            .setTcpIpConfig(tcpIpConfig);
    }

    private Config finalizeConfig(final Config config, final BaseHazelcastProperties hz) {
        if (StringUtils.hasText(hz.getCluster().getPartitionMemberGroupType())) {
            final PartitionGroupConfig partitionGroupConfig = config.getPartitionGroupConfig();
            final PartitionGroupConfig.MemberGroupType type = PartitionGroupConfig.MemberGroupType.valueOf(
                hz.getCluster().getPartitionMemberGroupType().toUpperCase());
            LOGGER.debug("Using partition member group type [{}]", type);
            partitionGroupConfig.setEnabled(true).setGroupType(type);
        }
        return config;
    }

    private DiscoveryStrategyConfig getDiscoveryStrategyConfigByJClouds(final HazelcastClusterProperties cluster) {
        final HazelcastJCloudsDiscoveryProperties jclouds = cluster.getDiscovery().getJclouds();
        final Map<String, Comparable> properties = new HashMap<>();
        if (StringUtils.hasText(jclouds.getCredential())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_CREDENTIAL, jclouds.getCredential());
        }
        if (StringUtils.hasText(jclouds.getCredentialPath())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_CREDENTIAL_PATH, jclouds.getCredentialPath());
        }
        if (StringUtils.hasText(jclouds.getEndpoint())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_ENDPOINT, jclouds.getEndpoint());
        }
        if (StringUtils.hasText(jclouds.getGroup())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_GROUP, jclouds.getGroup());
        }
        if (StringUtils.hasText(jclouds.getIdentity())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_IDENTITY, jclouds.getIdentity());
        }
        if (jclouds.getPort() > 0) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_HZ_PORT, jclouds.getPort());
        }
        if (StringUtils.hasText(jclouds.getProvider())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_PROVIDER, jclouds.getProvider());
        }
        if (StringUtils.hasText(jclouds.getRegions())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_REGIONS, jclouds.getRegions());
        }
        if (StringUtils.hasText(jclouds.getRoleName())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_ROLE_NAME, jclouds.getRoleName());
        }
        if (StringUtils.hasText(jclouds.getTagKeys())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_TAG_KEYS, jclouds.getTagKeys());
        }
        if (StringUtils.hasText(jclouds.getTagValues())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_TAG_VALUES, jclouds.getTagValues());
        }
        if (StringUtils.hasText(jclouds.getZones())) {
            properties.put(HazelcastJCloudsDiscoveryProperties.JCLOUDS_DISCOVERY_ZONES, jclouds.getZones());
        }
        return new DiscoveryStrategyConfig(new JCloudsDiscoveryStrategyFactory(), properties);
    }

    private DiscoveryStrategyConfig getDiscoveryStrategyConfigByAws(final HazelcastClusterProperties cluster) {
        final HazelcastAwsDiscoveryProperties aws = cluster.getDiscovery().getAws();
        final Map<String, Comparable> properties = new HashMap<>();
        if (StringUtils.hasText(aws.getAccessKey())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_ACCESS_KEY, aws.getAccessKey());
        }
        if (StringUtils.hasText(aws.getSecretKey())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_SECRET_KEY, aws.getSecretKey());
        }
        if (StringUtils.hasText(aws.getIamRole())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_IAM_ROLE, aws.getIamRole());
        }
        if (StringUtils.hasText(aws.getHostHeader())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_HOST_HEADER, aws.getHostHeader());
        }
        if (aws.getPort() > 0) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_PORT, aws.getPort());
        }
        if (StringUtils.hasText(aws.getRegion())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_REGION, aws.getRegion());
        }
        if (StringUtils.hasText(aws.getSecurityGroupName())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_SECURITY_GROUP_NAME, aws.getSecurityGroupName());
        }
        if (StringUtils.hasText(aws.getTagKey())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_TAG_KEY, aws.getTagKey());
        }
        if (StringUtils.hasText(aws.getTagValue())) {
            properties.put(HazelcastAwsDiscoveryProperties.AWS_DISCOVERY_TAG_VALUE, aws.getTagValue());
        }

        return new DiscoveryStrategyConfig(new AwsDiscoveryStrategyFactory(), properties);
    }

    private DiscoveryStrategyConfig getDiscoveryStrategyConfigByAzure(final HazelcastClusterProperties cluster) {
        final HazelcastAzureDiscoveryProperties azure = cluster.getDiscovery().getAzure();
        final Map<String, Comparable> properties = new HashMap<>();
        if (StringUtils.hasText(azure.getClientId())) {
            properties.put(HazelcastAzureDiscoveryProperties.AZURE_DISCOVERY_CLIENT_ID, azure.getClientId());
        }
        if (StringUtils.hasText(azure.getClientSecret())) {
            properties.put(HazelcastAzureDiscoveryProperties.AZURE_DISCOVERY_CLIENT_SECRET, azure.getClientSecret());
        }
        if (StringUtils.hasText(azure.getClusterId())) {
            properties.put(HazelcastAzureDiscoveryProperties.AZURE_DISCOVERY_CLUSTER_ID, azure.getClusterId());
        }
        if (StringUtils.hasText(azure.getGroupName())) {
            properties.put(HazelcastAzureDiscoveryProperties.AZURE_DISCOVERY_GROUP_NAME, azure.getGroupName());
        }
        if (StringUtils.hasText(azure.getSubscriptionId())) {
            properties.put(HazelcastAzureDiscoveryProperties.AZURE_DISCOVERY_SUBSCRIPTION_ID, azure.getSubscriptionId());
        }
        if (StringUtils.hasText(azure.getTenantId())) {
            properties.put(HazelcastAzureDiscoveryProperties.AZURE_DISCOVERY_TENANT_ID, azure.getTenantId());
        }
        return new DiscoveryStrategyConfig(new AzureDiscoveryStrategyFactory(), properties);
    }
}
