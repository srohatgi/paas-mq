package com.intocloudtech.paas.mq;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.Cluster;
import com.amazonaws.services.ecs.model.CreateClusterRequest;
import com.amazonaws.services.ecs.model.CreateClusterResult;

import com.amazonaws.services.ecs.model.DescribeClustersRequest;
import com.beust.jcommander.JCommander;

import com.beust.jcommander.Parameter;
import com.sun.tools.javac.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;


/**
 * build a spec for mq-service cluster
 * for launching with different recipes
 */
public class MQSpec {
    static final Logger logger = LoggerFactory.getLogger(MQSpec.class);

    @Parameter(names = { "--instances" }, description = "Instances of mq containers")
    int instances = 1;
    @Parameter(names = { "--storage" }, description = "Storage in GiB for each mq container")
    int gibStoragePerNode = 10;
    @Parameter(names = { "--cluster" }, description = "Cluster name")
    String clusterName = "j-sumeet";

    AmazonECS ecs;

    public static void main(String... args) {
        MQSpec spec = new MQSpec();
        new JCommander(spec, args);

        spec.ecs = AmazonECSClientBuilder
                .standard()
                .withCredentials(new ProfileCredentialsProvider())
                .withRegion(Regions.US_WEST_1).build();

        spec.runRecipe1();
    }

    void runRecipe1() {
        Cluster cluster = buildCluster();

        List<String> zkIps = bootZooKeeper(cluster);

        bootKafka(cluster, zkIps);
    }

    private void bootKafka(Cluster cluster, List<String> zkIps) {

    }

    Cluster buildCluster() {
        logger.debug("finding cluster: {}", clusterName);

        DescribeClustersRequest describe = new DescribeClustersRequest().withClusters(clusterName);

        Optional<Cluster> cluster = ecs.describeClusters(describe)
                .getClusters()
                .stream()
                .findAny();

        if (!cluster.isPresent()) {
            logger.info("creating cluster {}", clusterName);

            CreateClusterRequest request = new CreateClusterRequest().withClusterName(clusterName);

            CreateClusterResult result = ecs.createCluster(request);
            cluster = Optional.of(result.getCluster());
        }

        logger.debug("cluster exists: {}", cluster.get().getClusterName());

        return cluster.get();
    }

    private List<String> bootZooKeeper(Cluster cluster) {
        return null;
    }
}
