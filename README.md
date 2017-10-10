# Bitbucket to GitHib Enterprise migrator

Migrates projects from Bitbucket into a flat GitHub organization

## How it works
-   For each repository in a project in Bitbucket it creates new repository in GitHub with the name PROJECT_KEY.repo_slug
-   Creates teams based on the file (see below)
-   Assigns repositories to teams based on file (see below)
-   Migrates all open pull requests

## How to use
-   Usage help
    ```bash
    java -jar migrator.jar --help
    ```
    
-   Migrate all projects
    ```bash
    java -jar migrator.jar \
        --org=TheOrganizationName
        --tmpDir=/tmp/kekeke
        --threads=8
        --teams=/tmp/teams
        --teamsPermissions=/tmp/teams.permissions
        --stashUrl=https://url.to.stash.com
        --stashLogin=STASH_LOGIN
        --stashPassword=STASH_PASSWWORD
        --gitHubUrl=git@url.to.git.com
        --gitHubApiUrl=https://url.to.git.com/api/v3
        --token=GITHUB_ACCES_TOKEN
    ```
    
-   To migrate only particular projects (WITHOUT re-creating teams): add `--onlyProjects` with comma-separated list of project KEYS
-   To exclude particular projects: add `--excludeProjects` with comma-separated list of project KEYS
-   For debugging purposes you can add `--reset` parameter and that will remove all teams and all reposiories inside given organization

## What is `--teams` ?

The migrator will create teams based on that file

-   A file in the following format: 
    ```
    TEAM_NAME1
    MEMBER1
    MEMBER2
    BLANK_LINE
    TEAM_NAME2
    MEMBER1
    MEMBER3
    ...
    ```
    
-   For example:
    ```
    Starwars
    luke
    dark.lord
    
    Myteam
    anton.zagorskii    
    ```
    
## What is `--teams.permissions`

The migrator will assign repositories to teams based on that file. You need to provide projects keys from Bitbucket instead of repositories 

-   A file in the following format: 
    ```
    TEAM_NAME1
    PROJECT_KEY1
    PROJECT_KEY2
    BLANK_LINE
    TEAM_NAME2
    PROJECT_KEY1
    PROJECT_KEY4
    ...
    ```
    
-   For example:
    ```
    Starwars
    DTHSTR
    SHIPS
    
    Myteam
    NCLR
    ITJ
    ```

## Known issues/features
-   Might not preserve description of a pull-request
-   All new teams are secret 

## Questions
Ask Anton Zagorskii