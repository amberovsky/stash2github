package pro.amberovsky.stash2github;
/*
 * Stash to GitHub Enterprise migrator
 *
 * @author Anton Zagorskii amberovsky@gmail.com
 */
import com.atlassian.stash.rest.client.api.entity.Page;
import com.atlassian.stash.rest.client.api.entity.Project;
import com.atlassian.stash.rest.client.api.entity.PullRequestStatus;
import com.atlassian.stash.rest.client.api.entity.Repository;
import com.atlassian.stash.rest.client.core.StashClientImpl;
import com.atlassian.stash.rest.client.core.http.UriBuilder;
import com.atlassian.stash.rest.client.httpclient.HttpClientConfig;
import com.atlassian.stash.rest.client.httpclient.HttpClientHttpExecutor;
import com.google.gson.JsonElement;
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.kohsuke.github.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.atlassian.stash.rest.client.core.http.HttpMethod.GET;
import static com.atlassian.stash.rest.client.core.parser.Parsers.repositoryParser;
import static com.atlassian.stash.rest.client.core.parser.Parsers.pageParser;

/**
 * Waiting till the PR is released in maven https://bitbucket.org/atlassianlabs/stash-java-client/pull-requests/29/new-method-to-get-repositories-in-a/diff
 */
class StashClientImplementation extends StashClientImpl {
    StashClientImplementation(URL url, String username, String password) {
        super(new HttpClientHttpExecutor(new HttpClientConfig(url, username, password)));
    }

    public Page<Repository> getRepositoriesInProject(final String projectKey) {
        final UriBuilder uriBuilder = UriBuilder.forPath("/rest/api/1.0/projects/%s/repos", projectKey)
                .addQueryParam("start", "0")
                .addQueryParam("limit", "10000");

        JsonElement jsonElement = doRestCall(uriBuilder, GET, null, false);

        return pageParser(repositoryParser()).apply(jsonElement);
    }
}


public class Migrator {
    /** Command-line options */
    final private static Options cmdOptions = new Options();

    /** Comamnd line */
    private static CommandLine commandLine = null;

    /** Used for git cloning */
    final private String tmpDir;

    /** Maximum amount of created threads */
    final private int threads;

    /** Projects for migration from stash */
    private List<Project> projects;

    /** Map of repositories to be migrated */
    final private Map<Project, List<Repository>> repositories = new ConcurrentHashMap<>();

    /** Map of pull requests to be migrated */
    final private Map<Repository, List<PullRequestStatus>> pullRequests = new ConcurrentHashMap<>();

    /** Amount of total repositories to be migrated from stash */
    private AtomicInteger totalRepositories = new AtomicInteger(0);

    /** Amount of open pull requests to be migrated from stash */
    private AtomicInteger totalPullRequests = new AtomicInteger(0);

    /** How many repositories failed to migrate */
    private AtomicInteger repositoriesFailedToMigrate = new AtomicInteger(0);

    /** Current migration ID for a repository (order) */
    private AtomicInteger repositoryMigrationIndex = new AtomicInteger(0);

    /** List of teams to be created in GitHub */
    final private HashMap<String, List<String>> teams;

    /**
     * List of teams with the project keys from STASH where they should have write access
     * Maps FROM project key TO team name
     */
    final private HashMap<String, List<String>> teamsPermissions;

    /** Stash client */
    final private Supplier<StashClientImplementation> stashClientSupplier;

    /** GitHub client (thread-safe) */
    private GitHub gitHubClient;

    /** Target organization in GitHub */
    private GHOrganization gitHubOrganization;

    /** Organization name in github */
    final private String organizationName;

    /** git URL for GitHub */
    final private String gitHubURL;

    /** How much time to sleep (in millis) in case of unsuccessful call to stash/github */
    final private static int THREAD_SLEEP_TIME = 1000;

    /** Max anount of attempts of unsuccessfull calls to stash/github */
    final private static int MAX_ATTEMPTS = 10;

    /** Generates new thread ID */
    final private static IntSupplier generateThreadId = () -> (int) (Math.random() * 900) + 100;

    /**
     * Constructor
     */
    Migrator(
            final int threads,
            final String tmpDir,
            final HashMap<String, List<String>> teams,
            final HashMap<String, List<String>> teamsPermissions,
            final String organizationName,
            final String gitHubURL,
            final Supplier<StashClientImplementation> stashClientSupplier,
            final Supplier<GitHub> gitHubClientSupplier
    ) {
        this.threads = threads;
        this.stashClientSupplier = stashClientSupplier;
        this.tmpDir = tmpDir;
        this.teams = teams;
        this.teamsPermissions = teamsPermissions;
        this.organizationName = organizationName;
        this.gitHubURL = gitHubURL;
        this.gitHubClient = gitHubClientSupplier.get();
    }

    /**
     * Load info about all projects in stash and skip already migrated projects (as defined in {link migratedProjects})
     *
     * @param onlyProjects load only projects with given key
     * @param excludeProjects list of projects to exclude
     */
    private void loadProjects(final List<String> onlyProjects, final List<String> excludeProjects) {
        // Way 3: ExecutorService - SingleThreadExecutor + execute/Runnable
        ExecutorService executor = null;

        try {
            executor = Executors.newSingleThreadExecutor();
            executor.execute(() -> {
                Stream<Project> projectStream = stashClientSupplier.get()
                        .getAccessibleProjects(0, 1000000)
                        .getValues()
                        .stream()
                        .filter(project -> !excludeProjects.contains(project.getKey()));

                if (onlyProjects.size() > 0)
                    projectStream = projectStream.filter(project -> onlyProjects.contains(project.getKey()));

                projects = projectStream.collect(Collectors.toList());
            });
        } finally {
            if (executor != null) {
                executor.shutdown();

                // Wait for the task to finish
                while (!executor.isTerminated()) {
                    try {
                        executor.awaitTermination(1, TimeUnit.MINUTES);
                    } catch (InterruptedException exception) {
                        // Expected behaviour
                    }
                }
            }
        }
    }

    /**
     * Logger for project-related messages
     *
     * @param threadId thread ID
     * @param project project
     *
     * @return logger for project-related messages
     */
    private Consumer<String> getLoggerForProject(int threadId, Project project) {
        return message -> System.out.println(String.format(
                "%d %d %-13s : %s",
                Instant.now().getEpochSecond(),
                threadId,
                project.getKey(),
                message
        ));
    }

    /**
     * @param threadId thread ID
     * @param repository repository
     *
     * @return Logger for repository-related messages
     */
    private Consumer<String> getLoggerForRepository(int threadId, Repository repository) {
        return message -> System.out.println(String.format(
                "%d %d %-13s %-35s : %s",
                Instant.now().getEpochSecond(),
                threadId,
                repository.getProject().getKey(),
                repository.getName(),
                message
        ));
    }

    /**
     * @param threadId thread ID
     *
     * @return logger for general GitHub messages
     */
    private Consumer<String> getLoggerForGithub(int threadId) {
        return message -> System.out.println(threadId + " GitHub: " + message);
    }

    /**
     * @param repository in stash
     *
     * @return the new name of repository in GitHub
     */
    private String getGitHubRepositoryName(Repository repository) {
        return repository.getProject().getKey() + "." + repository.getSlug();
    }

    /**
     * Load repositories
     */
    private void loadRepositories() {
        // Way 4: Parallel streams
        projects.stream().parallel().forEach(project -> {
            int attempts = MAX_ATTEMPTS;

            Consumer<String> logger = getLoggerForProject(generateThreadId.getAsInt(), project);

            StashClientImplementation stashClient = stashClientSupplier.get();

            List<Repository> loadedRepositories = null;

            while ((loadedRepositories == null) && (attempts-- > 0)) {
                try {
                    loadedRepositories = stashClient.getRepositoriesInProject(project.getKey()).getValues();
                    totalRepositories.addAndGet(loadedRepositories.size());
                } catch (Exception exception) {
                    logger.accept("!!! failed to load repositories, attempts left: " + attempts + "; " +
                            exception.getMessage());

                    try {
                        Thread.sleep(THREAD_SLEEP_TIME);
                    } catch (Exception exception2) {
                        logger.accept(exception2.getMessage());
                    }
                }
            }

            if (loadedRepositories == null) {
                logger.accept("!!! Failed after " + MAX_ATTEMPTS + " attempts!");
                return;
            }

            repositories.put(project, loadedRepositories);

            logger.accept("Loaded " + loadedRepositories.size() + " repositories");
        });
    }

    /**
     * Load pull requests
     */
    private void loadPullRequests() {
        // Way 5: ExecutorService - FixedThreadPool + invokeAll/Callable
        List<Callable<Void>> tasks = new ArrayList<>(repositories.size());

        // List of tasks for the pool
        repositories.keySet().forEach(project -> tasks.add(() -> {
            StashClientImplementation stashClient = stashClientSupplier.get();

            repositories.get(project).forEach(repository -> {
                int attempts = MAX_ATTEMPTS;
                Consumer<String> logger = getLoggerForRepository(generateThreadId.getAsInt(), repository);
                List<PullRequestStatus> loadedPullRequests = null;

                while ((loadedPullRequests == null) && (attempts-- > 0)) {
                    try {
                        loadedPullRequests = stashClient.getPullRequestsByRepository(
                                project.getKey(),
                                repository.getSlug(),
                                null,
                                null,
                                com.atlassian.stash.rest.client.api.StashClient.PullRequestStateFilter.OPEN,
                                null,
                                0,
                                100000,
                                null
                        ).getValues();

                    } catch (Exception exception) {
                        logger.accept("!!! failed to load pull requests, attempts left: " + attempts + "; " +
                                exception.getMessage());

                        try {
                            Thread.sleep(THREAD_SLEEP_TIME);
                        } catch (Exception exception2) {
                            logger.accept(exception2.getMessage());
                        }
                    }
                }

                if (loadedPullRequests == null) {
                    logger.accept("!!! Failed after " + MAX_ATTEMPTS + " attempts!");
                    return;
                }


                pullRequests.put(repository, loadedPullRequests);

                totalPullRequests.addAndGet(loadedPullRequests.size());
                if (loadedPullRequests.size() > 0)
                    logger.accept("Loaded " + loadedPullRequests.size() + " open PRs");
            });

            return null;
        }));

        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(threads);
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    /**
     * Migrates repositories
     */
    private void migrateRepositories() {
        List<Callable<Void>> tasks = new ArrayList<>(repositories.size());

        // List of tasks for the pool
        repositories.keySet().forEach(project ->
            repositories.get(project).forEach(repository -> tasks.add(() -> {
                Consumer<String> logger_ = getLoggerForRepository(generateThreadId.getAsInt(), repository);

                final int migrationIndex = repositoryMigrationIndex.incrementAndGet();
                Consumer<String> logger = message -> logger_.accept("(" + migrationIndex + "/" +
                        totalRepositories + ") " + message);

                logger.accept("Starting...");

                try {
                    String newRepoName = getGitHubRepositoryName(repository);
                    File path = new File(tmpDir + "/" + newRepoName).getAbsoluteFile();

                    // Creating new repo in github
                    GHRepository ghRepository = gitHubOrganization.createRepository(newRepoName)
                            .description(repository.getName())
                            .private_(true)
                            .wiki(false)
                            .issues(false)
                            .create();

                    // Clone locally
                    Process cloning = Runtime.getRuntime().exec(
                            new String[]{"git", "clone", "--mirror", repository.getSshCloneUrl(), path.toString()}
                    );
                    int retCode = cloning.waitFor();

                    if (retCode != 0) {
                        repositoriesFailedToMigrate.incrementAndGet();

                        logger.accept("!!! Unable to clone, output is:");

                        BufferedReader stdInput = new BufferedReader(new InputStreamReader(cloning.getInputStream()));
                        BufferedReader stdError = new BufferedReader(new InputStreamReader(cloning.getErrorStream()));

                        String line;
                        while ((line = stdInput.readLine()) != null) {
                            logger.accept("!!! " + line);
                        }

                        while ((line = stdError.readLine()) != null) {
                            logger.accept("!!! " + line);
                        }

                        return null;
                    }
//                    logger.accept("Cloned locally to " + path.toString());

                    // Push into github
                    Process pushing = Runtime.getRuntime().exec(
                            new String[]{
                                    "git",
                                    "push",
                                    "--mirror",
                                    gitHubURL + ":" + organizationName + "/" + newRepoName + ".git"
                            },
                            null,
                            path
                    );
                    retCode = pushing.waitFor();


                    if (retCode != 0) {
                        repositoriesFailedToMigrate.incrementAndGet();

                        logger.accept("!!! Unable to push, output is:");

                        BufferedReader stdInput = new BufferedReader(new InputStreamReader(pushing.getInputStream()));
                        BufferedReader stdError = new BufferedReader(new InputStreamReader(pushing.getErrorStream()));

                        String line;
                        while ((line = stdInput.readLine()) != null) {
                            logger.accept("!!! " + line);
                        }

                        while ((line = stdError.readLine()) != null) {
                            logger.accept("!!! " + line);
                        }
                    }

                    try {
                        FileUtils.deleteDirectory(path);
                    } catch (IOException exception) {
                        logger.accept("!!! Can't delete local folder " + path.toString() + " ; error is " +
                                exception.getMessage());
                    }

                    if (retCode == 0) {
                        // Assign to a team
                        List<String> assignedTeams = new ArrayList<>();

                        for (String teamName : teamsPermissions.get(project.getKey())) {
                            try {
                                GHTeam team = gitHubOrganization.getTeamByName(teamName);
                                team.add(ghRepository, GHOrganization.Permission.PUSH);
                                assignedTeams.add(teamName);
                            } catch (IOException exception) {
                                logger.accept("!!! Can't load / assign repository " + newRepoName + " to the team " +
                                        teamName);
                            }
                        }

                        logger.accept("Migrated to " + newRepoName + " Assigned to teams [" +
                                String.join(", ", assignedTeams) + "]");
                    }
                } catch (IOException exception) {
                    logger.accept("!!! " + exception.getMessage());
                    repositoriesFailedToMigrate.incrementAndGet();
                }

                return null;
            }))
        );

        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(threads);
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    /**
     * Migrate current open pull requests
     */
    private void migratePullRequests() {
        List<Callable<Void>> tasks = new ArrayList<>(repositories.size());

        pullRequests.keySet().forEach(repository ->
            tasks.add(() -> {
            String newRepositoryName = getGitHubRepositoryName(repository);
                GHRepository ghRepository = gitHubOrganization.getRepository(newRepositoryName);
                pullRequests.get(repository).forEach(pullRequest -> {
                    Consumer<String> logger = getLoggerForRepository(generateThreadId.getAsInt(), repository);
                    try {
                        ghRepository.createPullRequest(
                                pullRequest.getTitle(),
                                pullRequest.getFromRef().getId(),
                                pullRequest.getToRef().getId(),
                                "AUTO-GENERATED during the migration from Stash to GitHub. The description " +
                                        "maybe lost"
                        );

                        logger.accept("Created pull request for repository " + newRepositoryName + " from " +
                                pullRequest.getFromRef().getId() + " to " + pullRequest.getToRef().getId());
                    } catch (IOException exception) {
                        logger.accept("!!! Unable to create pull request for repository " + newRepositoryName +
                                " from " + pullRequest.getFromRef().getId() + " to " + pullRequest.getToRef().getId() +
                                "\nError is: " + exception.getMessage());
                    }
                });

                return null;
            })
        );

        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(threads);
            executor.invokeAll(tasks);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (executor != null) executor.shutdown();
        }
    }

    /**
     * Create and populate teams in GitHub
     */
    private void createAndsPopulateTeams() {
        ExecutorService executor = null;
        try {
            executor = Executors.newFixedThreadPool(threads);

            for (String teamName : teams.keySet()) {
                executor.execute(() -> {
                    int added = 0;

                    try {
                        Consumer<String> logger = getLoggerForGithub(generateThreadId.getAsInt());

                        // Create team
                        GHTeam team = gitHubOrganization.createTeam(teamName, GHOrganization.Permission.PULL);
                        logger.accept("Team [" + teamName + "] was created");

                        // Add members
                        for (String member : teams.get(teamName)) {
                            String gitHubMemberName = member.replace('.', '-');
                            try {
                                // getUser is thread-safe
                                team.add(gitHubClient.getUser(gitHubMemberName), GHTeam.ROLE.MEMBER);
                                added++;
                            } catch (GHFileNotFoundException exception) {
                                logger.accept("!!! Can't add user [" + member + " / " + gitHubMemberName + "] to " +
                                        "the team [" + teamName + "] - the user does not exist, SKIPPING");
                            }
                        }

                        logger.accept("Added " + added + " users to the [" + teamName + "] team");
                    }
                    catch (IOException exception) {
                        System.out.println(exception.getMessage());
                        throw new RuntimeException(exception);
                    }
                });
            }
        } finally {
            if (executor != null) {
                executor.shutdown();

                // Wait for the task to finish
                while (!executor.isTerminated()) {
                    try {
                        executor.awaitTermination(5, TimeUnit.MINUTES);
                    } catch (InterruptedException exception) {
                        // Expected behaviour
                    }
                }
            }
        }
    }

    /**
     * Init migrator
     */
    private void init() {
        try {
            gitHubOrganization = gitHubClient.getOrganization(organizationName);
        } catch (IOException exception) {
            System.out.println("!!! Can't access [" + organizationName + "] organization in github");
            throw new RuntimeException(exception);
        }
    }

    /**
     * Remove teams
     */
    private int removeTeams() {
        int teamsRemoved = 0;

        try {
            Map<String, GHTeam> teams = gitHubOrganization.getTeams();
            for (GHTeam team : teams.values()) {
                team.delete();
                teamsRemoved++;
            }
        } catch (IOException exception) {
            System.out.println("!!! " + exception.getMessage());
            throw new RuntimeException(exception);
        }

        return teamsRemoved;
    }

    /**
     * Remove repositories
     */
    private int removeRepositories() {
        int repositoriesRemoved = 0;

        try {
            Map<String, GHRepository> repositories = gitHubOrganization.getRepositories();
            for (GHRepository repository : repositories.values()) {
                repository.delete();
                repositoriesRemoved++;
            }
        } catch (IOException exception) {
            System.out.println("!!! " + exception.getMessage());
            throw new RuntimeException(exception);
        }

        return repositoriesRemoved;
    }

    /**
     * Reset GitHub (removes all repos, teams). For debugging purposes.
     */
    private void resetGitHub() {
        init();

        // 1. Remove teams
        System.out.println("STEP 1/2: REMOVING TEAMS...");
        int teamsRemoved = removeTeams();
        System.out.println("" + teamsRemoved + " teams have been removed\n\n");

        // 1. Remove repositories
        System.out.println("STEP 2/2: REMOVING REPOSITORIES...");
        int repositoriesRemoved = removeRepositories();
        System.out.println("" + repositoriesRemoved + " repositories have been removed\n\n");

        System.out.println("Done!");
    }

    /**
     * Run the migration
     *
     * @param onlyProjects migrate only those projects, do not create teams
     * @param excludeProjects list of projects to exclude
     */
    public void runMigration(final List<String> onlyProjects, final List<String> excludeProjects) {
        // 0. Init
        if (gitHubOrganization == null) init();

        // 1. Load projects
        System.out.println("STEP 1/6: LOADING PROJECTS...");
        loadProjects(onlyProjects, excludeProjects);
        System.out.println("Loaded " + projects.size() + " projects\n\n");

        // 2. Load repositories
        System.out.println("STEP 2/6: LOADING REPOSITORIES...");
        loadRepositories();
        System.out.println("In total: " + projects.size() + " projects and " + totalRepositories +
                " repositories are going to be migrated\n\n");

        // 3. Load pull requests
        System.out.println("STEP 3/6: LOADING PULL REQUESTS...");
        loadPullRequests();
        System.out.println("In total: " + projects.size() + " projects and " + totalRepositories +
                " and repositories " + totalPullRequests + " pull requests are going to be migrated\n\n");

        // 4. Create and populate teams
        System.out.println("STEP 4/6: CREATING AND POPULATING TEAMS...");
        if (onlyProjects.size() == 0) createAndsPopulateTeams();
        else System.out.println("SKIPPED DUE TO ONLY ONE PROJECT MIGRATION");
        System.out.println("\n\n");

        // 5. Migrate repositories
        System.out.println("STEP 5/6: MIGRATING REPOSITORIES...");
        migrateRepositories();
        System.out.println("Repositories have been migrated; " + repositoriesFailedToMigrate.get() + " failed to " +
                "migrate \n\n");

        // 6. Migrate pull requests
        System.out.println("STEP 6/6: MIGRATING PULL REQUESTS...");
        migratePullRequests();
        System.out.println("\n\n");

        System.out.println("That's all, folks!");
    }

    /**
     * Display how to use this script
     */
    private static void showHelp() {
        HelpFormatter helpFormatter = new HelpFormatter();
        helpFormatter.setWidth(120);
        helpFormatter.printHelp("java -jar migrator.jar", cmdOptions);
    }

    /**
     * Adds available options for command-line
     */
    private static void populateOptions() {
        cmdOptions.addOption(Option.builder().longOpt("help").desc("This help").build());

        cmdOptions.addOption(Option.builder()
                .longOpt("onlyProjects")
                .hasArg().argName("project_keys")
                .desc("Migrate ONLY given project (comma-separated). No new teams will be created, but assigned")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .longOpt("excludeProjects")
                .hasArg().argName("project_keys")
                .desc("Exclude projects from migration (comma-separated)")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .longOpt("reset")
                .desc("RESET GitHub state (remove all teams and all repositories in the given organization)")
                .build()
        );

        cmdOptions.addOption(Option.builder().
                required().
                longOpt("tmpDir").
                hasArg().argName("path").
                desc("Tmp directory for git operations").
                build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("threads")
                .hasArg().argName("number")
                .desc("How many threads to use for git operations")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("teams")
                .hasArg().argName("path")
                .desc("Path to file with teams/members")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("teamsPermissions")
                .hasArg().argName("path")
                .desc("Path to file with teams/permissions")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("stashUrl")
                .hasArg().argName("url")
                .desc("URL to stash")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("stashLogin")
                .hasArg().argName("login")
                .desc("Stash login")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("stashPassword")
                .hasArg().argName("password")
                .desc("Stash password")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("gitHubUrl")
                .hasArg().argName("url")
                .desc("URL to GitHub in git format like git@domain.com")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("gitHubApiUrl")
                .hasArg().argName("url")
                .desc("URL to GitHub API like https://github.com/api/v3")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("token")
                .hasArg().argName("token")
                .desc("GitHub access token")
                .build()
        );

        cmdOptions.addOption(Option.builder()
                .required()
                .longOpt("org")
                .hasArg().argName("org")
                .desc("Organization name in GitHub")
                .build()
        );
    }

    /**
     * Entrypoint
     *
     * @param args args
     */
    static public void main(String[] args) throws IOException {
        // Parse command line
        populateOptions();


        try {
            commandLine = new DefaultParser().parse(cmdOptions, args);
            if (commandLine.hasOption("help")) {
                showHelp();
                return;
            }

        } catch (ParseException exception) {
            System.out.println(exception.getMessage());
            showHelp();
            return;
        }


        final int threads = Integer.parseInt(commandLine.getOptionValue("threads")) + 2;

        System.setProperty("java.util.concurrent.ForkJoinPool.common.parallelism", String.valueOf(threads));

        // List of teams with their members
        final HashMap<String, List<String>> teams = new HashMap<>();

        // Loading what should be teams in github
        String currentTeam = null;
        for (String line : Files.readAllLines(Paths.get(commandLine.getOptionValue("teams")))) {
            if (currentTeam == null) {
                teams.put(line, new ArrayList<>());
                currentTeam = line;
            } else {
                if (line.isEmpty()) currentTeam = null;
                else teams.get(currentTeam).add(line);
            }
        }


        // List of teams with the project keys from STASH where they should have write access
        // Maps FROM project key TO team name
        final HashMap<String, List<String>> teamsPermissions = new HashMap<>();
        currentTeam = null;
        for (String line : Files.readAllLines(Paths.get(commandLine.getOptionValue("teamsPermissions")))) {
            if (currentTeam == null) {
                currentTeam = line;
            } else {
                if (line.isEmpty()) currentTeam = null;
                else {
                    teamsPermissions.putIfAbsent(line, new ArrayList<>());
                    teamsPermissions.get(line).add(currentTeam);
                }
            }
        }


        // Way 0: ExecutorService - SingleThreadExecutor + submit/Callable<T>
        ExecutorService executor = null;
        Migrator migrator;

        try {
            executor = Executors.newSingleThreadExecutor();
            Future<Migrator> stashgithubFuture = executor.submit(() -> new Migrator(
                    threads,
                    commandLine.getOptionValue("tmpDir"),
                    teams,
                    teamsPermissions,
                    commandLine.getOptionValue("org"),
                    commandLine.getOptionValue("gitHubUrl"),
                    () -> {
                        try {
                            return new StashClientImplementation(
                                    new URL(commandLine.getOptionValue("stashUrl")),
                                    commandLine.getOptionValue("stashLogin"),
                                    commandLine.getOptionValue("stashPassword")
                            );
                        } catch (MalformedURLException exception) {
                            throw new RuntimeException(exception);
                        }
                    },
                    () -> {
                        try {
                            return GitHub.connectToEnterprise(
                                    commandLine.getOptionValue("gitHubApiUrl"),
                                    commandLine.getOptionValue("token")
                            );
                        } catch (IOException exception) {
                            throw new RuntimeException(exception);
                        }
                    }
            ));

            migrator = stashgithubFuture.get(1, TimeUnit.MINUTES);

        } catch (TimeoutException | InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        } finally {
            if (executor != null) executor.shutdown();
        }

        // Way 1: Extend Thread class and override run method
        new Thread() {
            @Override
            public void run() {

                if (commandLine.hasOption("reset")) {
                    migrator.resetGitHub();
                }


                    // Way 2: Create Thread object and pass Runnable lambda into constructor
                new Thread(() -> migrator.runMigration(
                        Arrays.asList(commandLine.getOptionValue("onlyProjects", ",").split(",")),
                        Arrays.asList(commandLine.getOptionValue("excludeProjects", ",").split(","))
                        )
                ).start();
//                }
            }
        }.start();
    }
}
