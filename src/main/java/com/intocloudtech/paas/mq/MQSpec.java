package com.intocloudtech.paas.mq;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.*;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.util.List;
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
    int instances = 3;
    @Parameter(names = { "--storage" }, description = "Storage in GiB for each mq container")
    int gibStoragePerNode = 10;
    @Parameter(names = { "--cluster" }, description = "Cluster name")
    String clusterName = "j-sumeet";
    @Parameter(names = { "--recipe" }, description = "Recipe to use")
    String recipeName = "recipe1";

    public static void main(String... args) {
        MQSpec spec = new MQSpec();
        new JCommander(spec, args);

        Recipe recipe = null;
        switch(spec.recipeName) {
            case "recipe1":
            default:
                logger.debug("running recipe1");
                recipe = new Recipe1(spec);
        }

        Cluster cluster = recipe.buildCluster();
        List<String> zkIps = recipe.bootZooKeeper(cluster);
        recipe.bootKafka(cluster, zkIps);
    }
}

class Recipe1 implements Recipe {
    static final Logger logger = LoggerFactory.getLogger(Recipe1.class);
    private final MQSpec spec;
    private final AmazonECS ecs;

    Recipe1(MQSpec spec) {
        this.spec = spec;
        this.ecs = AmazonECSClientBuilder
                .standard()
                .withCredentials(new ProfileCredentialsProvider())
                .withRegion(Regions.US_WEST_1).build();
    }

    public Cluster buildCluster() {
        logger.debug("finding cluster: {}", spec.clusterName);

        DescribeClustersRequest describe = new DescribeClustersRequest().withClusters(spec.clusterName);

        Optional<Cluster> cluster = ecs.describeClusters(describe)
                .getClusters()
                .stream()
                .findAny();

        if (!cluster.isPresent()) {
            logger.info("creating cluster {}", spec.clusterName);

            CreateClusterRequest request = new CreateClusterRequest()
                    .withClusterName(spec.clusterName);

            CreateClusterResult result = ecs.createCluster(request);
            cluster = Optional.of(result.getCluster());
        }

        logger.debug("cluster exists: {}", cluster.get().getClusterName());

        return cluster.get();
    }

    public List<String> bootZooKeeper(Cluster cluster) {
        // are there enough containers?
        int servers = cluster.getRegisteredContainerInstancesCount();

        RegisterContainerInstanceRequest request = new RegisterContainerInstanceRequest()
                .withCluster(cluster.getClusterArn())
                .withContainerInstanceArn("");

        ecs.registerContainerInstance(request);
        return null;
    }

    public void bootKafka(Cluster cluster, List<String> zkIps) {

    }
}