package com.intocloudtech.paas.mq;

import com.amazonaws.auth.profile.ProfileCredentialsProvider;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.ecs.AmazonECS;
import com.amazonaws.services.ecs.AmazonECSClientBuilder;
import com.amazonaws.services.ecs.model.*;

import com.amazonaws.services.identitymanagement.AmazonIdentityManagement;
import com.amazonaws.services.identitymanagement.AmazonIdentityManagementClientBuilder;
import com.amazonaws.services.identitymanagement.model.*;
import com.amazonaws.util.EC2MetadataUtils;
import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Scanner;

import static java.util.Collections.singletonList;


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
    @Parameter(names = { "--profile" }, description = "aws credential profile")
    String profile = "bolaris";
    @Parameter(names = { "--keypair" }, description = "key pair file to use")
    String keyPairFilepath;

    public static void main(String... args) {
        MQSpec spec = new MQSpec();
        new JCommander(spec, args);

        if (!new File(spec.keyPairFilepath).canRead()) {
            logger.error("please provide a key-pair file (--keypair) that can be read!");
            System.exit(2);
        }

        Recipe recipe = null;

        switch(spec.recipeName) {
            case "recipe1":
            default:
                logger.debug("running recipe1");
                recipe = new WeaveECS(spec);
        }

        recipe.execute();
    }
}

class WeaveECS implements Recipe {
    static final Logger logger = LoggerFactory.getLogger(WeaveECS.class);
    private final MQSpec spec;
    private final AmazonECS ecs;
    private final AmazonEC2 ec2;
    private final AmazonIdentityManagement iam;

    WeaveECS(MQSpec spec) {
        ProfileCredentialsProvider provider = new ProfileCredentialsProvider(spec.profile);

        this.spec = spec;
        this.ecs = AmazonECSClientBuilder
                .standard()
                .withCredentials(provider)
                .build();

        this.ec2 = AmazonEC2ClientBuilder
                .standard()
                .withCredentials(provider)
                .build();

        this.iam = AmazonIdentityManagementClientBuilder
                .standard()
                .withCredentials(provider)
                .build();
    }

    @Override
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

            Vpc vpc = buildVpc();
        }

        logger.debug("cluster exists: {}", cluster.get().getClusterName());

        return cluster.get();
    }

    void bootServers(Vpc vpc) {
        final String roleDoc = readDocument("ecs-role.json");

        iam.createRole(
                new CreateRoleRequest()
                        .withRoleName(spec.clusterName)
                        .withAssumeRolePolicyDocument(roleDoc));

        final String policyDoc = readDocument("ecs-policy.json");

        iam.createPolicy(
                new CreatePolicyRequest()
                        .withPolicyName(spec.clusterName)
                        .withPolicyDocument(policyDoc));

        iam.createInstanceProfile(
                new CreateInstanceProfileRequest()
                        .withInstanceProfileName(spec.clusterName));

        // wait for instance profile to be ready
        iam.getInstanceProfile(
                new GetInstanceProfileRequest()
                        .withInstanceProfileName(spec.clusterName))
                .getInstanceProfile();

        iam.addRoleToInstanceProfile(
                new AddRoleToInstanceProfileRequest()
                        .withRoleName(spec.clusterName)
                        .withInstanceProfileName(spec.clusterName));
    }

    private String readDocument(String fileName) {
        try (InputStream in = this
                .getClass()
                .getClassLoader()
                .getResourceAsStream(fileName)) {
            return new Scanner(in).useDelimiter("\\A").next();
        } catch (IOException ex) {
            throw new RuntimeException("unable to read file: " + fileName, ex);
        }
    }

    Vpc buildVpc() {
        final String cidr = "172.31.0.0/28";

        logger.info("creating vpn {}-vpc", spec.clusterName);

        Vpc vpc = ec2.createVpc(new CreateVpcRequest().withCidrBlock(cidr))
                .getVpc();

        ec2.modifyVpcAttribute(new ModifyVpcAttributeRequest()
                .withVpcId(vpc.getVpcId())
                .withEnableDnsSupport(true));

        ec2.modifyVpcAttribute(new ModifyVpcAttributeRequest()
                .withVpcId(vpc.getVpcId())
                .withEnableDnsHostnames(true));

        createTag(vpc.getVpcId(), "Name", spec.clusterName + "-vpc");

        Subnet subnet = ec2.createSubnet(new CreateSubnetRequest(vpc.getVpcId(), cidr)).getSubnet();

        createTag(subnet.getSubnetId(), "Name", spec.clusterName + "-subnet");

        InternetGateway ig = ec2.createInternetGateway().getInternetGateway();

        createTag(ig.getInternetGatewayId(), "Name", spec.clusterName);

        ec2.attachInternetGateway(
                new AttachInternetGatewayRequest()
                        .withVpcId(vpc.getVpcId())
                        .withInternetGatewayId(ig.getInternetGatewayId()));

        RouteTable routeTable = ec2
                .describeRouteTables(
                        new DescribeRouteTablesRequest()
                                .withFilters(new Filter("vpc", singletonList(vpc.getVpcId()))))
                .getRouteTables()
                .get(0);

        ec2.createRoute(
                new CreateRouteRequest()
                        .withRouteTableId(routeTable.getRouteTableId())
                        .withDestinationCidrBlock("0.0.0.0/0")
                        .withGatewayId(ig.getInternetGatewayId()));

        logger.info("creating security group: {}", spec.clusterName);

        String groupId = ec2
                .createSecurityGroup(
                        new CreateSecurityGroupRequest(spec.clusterName, "security-group")
                                .withVpcId(vpc.getVpcId()))
                .getGroupId();

        ec2.authorizeSecurityGroupIngress(
                new AuthorizeSecurityGroupIngressRequest(spec.clusterName,
                        Arrays.asList(
                                buildExternalPermission("tcp", 22, "0.0.0.0/0"),
                                buildExternalPermission("tcp", 80, "0.0.0.0/0"),
                                buildExternalPermission("tcp", 4040, "0.0.0.0/0"),
                                buildWithinPermission("tcp", 6783, groupId),
                                buildWithinPermission("udp", 6783, groupId),
                                buildWithinPermission("udp", 6784, groupId),
                                buildWithinPermission("tcp", 4040, groupId))));

        return vpc;
    }

    static IpPermission buildWithinPermission(String protocol, int port, String groupId) {
        return new IpPermission()
                .withIpProtocol(protocol)
                .withFromPort(port)
                .withToPort(port)
                .withUserIdGroupPairs(new UserIdGroupPair().withGroupId(groupId));
    }


    static IpPermission buildExternalPermission(String protocol, int port, String cidr) {
        return new IpPermission()
                .withIpProtocol(protocol)
                .withFromPort(port)
                .withToPort(port)
                .withIpv4Ranges(new IpRange().withCidrIp(cidr));
    }

    void createTag(String resourceId, String name, String value) {
        ec2.createTags(new CreateTagsRequest(singletonList(resourceId),
                singletonList(new Tag(name, value))));
    }

    @Override
    public List<String> bootZooKeeper(Cluster cluster) {
        // are there enough containers?
        int servers = cluster.getRegisteredContainerInstancesCount();

        RegisterContainerInstanceRequest request = new RegisterContainerInstanceRequest()
                .withCluster(cluster.getClusterArn())
                .withContainerInstanceArn("");

        ecs.registerContainerInstance(request);
        return null;
    }

    @Override
    public void bootKafka(Cluster cluster, List<String> zkIps) {

    }
}