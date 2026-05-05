declare const _default: {
    common: {
        save: string;
        saving: string;
        saved: string;
        cancel: string;
        loading: string;
        signOut: string;
        signIn: string;
        profile: string;
        home: string;
        backToHome: string;
    };
    header: {
        connection: {
            connected: string;
            occupied: string;
            idle: string;
        };
        menu: {
            languageHeader: string;
        };
    };
    login: {
        autoLoginNotice: string;
        invalidCredentials: string;
        loginFailed: string;
        loginFailedWithStatus: string;
        autoLoginFailed: string;
        tenant: string;
        username: string;
        password: string;
        rememberUser: string;
    };
    index: {
        sectionTitle: string;
        chat: {
            title: string;
            description: string;
        };
        documents: {
            title: string;
            description: string;
        };
        workspace: {
            title: string;
            description: string;
        };
        inbox: {
            title: string;
            description: string;
        };
        scopes: {
            title: string;
            description: string;
        };
        tools: {
            title: string;
            description: string;
        };
        insights: {
            title: string;
            description: string;
        };
        users: {
            title: string;
            description: string;
        };
        open: string;
    };
    chat: {
        pageTitle: string;
        backToSessions: string;
        connecting: string;
        confirmLeave: string;
        pickAnotherSession: string;
        tryAgain: string;
        backToPicker: string;
        historyLoading: string;
        composerPlaceholderSingle: string;
        composerPlaceholderMulti: string;
        send: string;
        pause: string;
        pauseTooltip: string;
        multilineToggleSingle: string;
        multilineToggleMulti: string;
        failedToOpen: string;
        failedToResume: string;
        failedToSend: string;
        connectionLost: string;
        connectionClosed: string;
        sessionOccupiedBy: string;
        sessionNotFound: string;
        sessionForbidden: string;
        speech: {
            startSpeechToText: string;
            stopSpeechToText: string;
            muteIncoming: string;
            readAloud: string;
            settings: string;
            language: string;
            voice: string;
            voiceAuto: string;
            voiceDefaultSuffix: string;
            rate: string;
            volume: string;
            savedLocally: string;
            microphoneError: string;
            recordStartFailed: string;
        };
        picker: {
            projectsTitle: string;
            loading: string;
            ungrouped: string;
            noProjects: string;
            noProjectsBody: string;
            pickAProject: string;
            signedInAs: string;
            newSession: string;
            sessionsLoading: string;
            noSessions: string;
            noSessionsBody: string;
            occupied: string;
            occupiedTooltip: string;
            available: string;
            failedToLoadSessions: string;
            failedToStartSession: string;
            relativeJustNow: string;
            relativeMinutes: string;
            relativeHours: string;
            relativeDays: string;
        };
        progress: {
            title: string;
            empty: string;
            emptyBody: string;
            metricsLine: string;
            metricsLabel: string;
            planUpdated: string;
            status: string;
        };
    };
    documents: {
        pageTitle: string;
        breadcrumbRoot: string;
        selectAProject: string;
        foldersTitle: string;
        folderAll: string;
        foldersEmptyHint: string;
        noProjectsHeadline: string;
        noProjectsBody: string;
        pickAProjectHeadline: string;
        pickAProjectBody: string;
        backToList: string;
        pathFilterPlaceholder: string;
        allKinds: string;
        clearFilter: string;
        newDocument: string;
        noDocumentsHeadline: string;
        noDocumentsBody: string;
        createFirstDocument: string;
        ungrouped: string;
        storedNote: string;
        detail: {
            sizeBy: string;
            kindBadgeTooltip: string;
            kindLabel: string;
            frontMatter: string;
            readOnlyNote: string;
            titleLabel: string;
            pathLabel: string;
            pathHelp: string;
            contentLabel: string;
            tabRaw: string;
            tabList: string;
            tabTree: string;
            tabMindmap: string;
            tabRecords: string;
            tabGraph: string;
            tabSheet: string;
            listParseError: string;
            treeParseError: string;
            mindmapParseError: string;
            recordsParseError: string;
            graphParseError: string;
            sheetParseError: string;
            delete: string;
            deletePermanent: string;
            download: string;
            cancel: string;
            apply: string;
            save: string;
        };
        listEditor: {
            addItem: string;
            deleteItem: string;
            clickToEdit: string;
            emptyItem: string;
            dragHandle: string;
            selectedCountSingular: string;
            selectedCountPlural: string;
            deleteSelected: string;
            clearSelection: string;
        };
        treeEditor: {
            addItem: string;
            addChild: string;
            deleteItem: string;
            clickToEdit: string;
            emptyItem: string;
            dragHandle: string;
            expand: string;
            collapse: string;
        };
        mindmapView: {
            panZoomHint: string;
        };
        sheetView: {
            addRow: string;
            addColumn: string;
            deleteRow: string;
            deleteColumn: string;
            hint: string;
            cellProps: string;
            cellEmptyHint: string;
            colorField: string;
            backgroundField: string;
            clear: string;
            clearFormat: string;
            emptySelectionHint: string;
        };
        graphView: {
            addNode: string;
            autoLayout: string;
            autoLayoutHint: string;
            directed: string;
            hint: string;
            nodeProps: string;
            edgeProps: string;
            labelField: string;
            colorField: string;
            clearColor: string;
            deleteNode: string;
            deleteEdge: string;
            idEmpty: string;
            idDuplicate: string;
            emptySelectionHint: string;
        };
        recordsEditor: {
            addRow: string;
            deleteItem: string;
            clickToEdit: string;
            emptyCell: string;
            dragHandle: string;
            selectedCountSingular: string;
            selectedCountPlural: string;
            deleteSelected: string;
            clearSelection: string;
            overflowHint: string;
            editSchema: string;
            doneEditingSchema: string;
            addColumn: string;
            deleteColumn: string;
            moveColumnLeft: string;
            moveColumnRight: string;
            schemaEmptyName: string;
            schemaDuplicateName: string;
            schemaMinOneColumn: string;
        };
        create: {
            newDocument: string;
            typeContent: string;
            uploadFile: string;
            pathLabel: string;
            pathPlaceholder: string;
            pathHelp: string;
            pathPlaceholderUpload: string;
            pathHelpUpload: string;
            titleLabel: string;
            titlePlaceholder: string;
            tagsLabel: string;
            tagsPlaceholder: string;
            tagsHelp: string;
            tagsHelpMulti: string;
            typeLabel: string;
            kindLabel: string;
            kindNone: string;
            kindHelp: string;
            contentLabel: string;
            inlineSizeNote: string;
            filesLabel: string;
            filesHelp: string;
            pathRequired: string;
            contentRequired: string;
            pickAtLeastOneFile: string;
            multiUploadFailed: string;
            uploadFailed: string;
            cancel: string;
            submitCreate: string;
            submitUpload: string;
        };
        delete: {
            title: string;
            body: string;
            cancel: string;
            confirm: string;
            titlePermanent: string;
            bodyPermanent: string;
            confirmPermanent: string;
        };
        help: {
            title: string;
            loading: string;
            unavailable: string;
            empty: string;
        };
        preview: {
            pdfRendering: string;
            pdfError: string;
            binary: string;
        };
        mime: {
            groupDoc: string;
            groupCode: string;
            groupWeb: string;
        };
    };
    insights: {
        pageTitle: string;
        loading: string;
        filters: {
            project: string;
            user: string;
            userPlaceholder: string;
            status: string;
            allProjects: string;
            all: string;
        };
        sidebar: {
            loadingSessions: string;
            noSessionsHeadline: string;
            noSessionsBody: string;
        };
        emptyMain: {
            headline: string;
            body: string;
        };
        breadcrumbs: {
            sessionPrefix: string;
            processPrefix: string;
            processFallback: string;
        };
        tabs: {
            overview: string;
            processes: string;
            timeline: string;
            chat: string;
            memory: string;
            tree: string;
            llmTrace: string;
        };
        session: {
            detailsTitle: string;
            mongoId: string;
            user: string;
            project: string;
            status: string;
            client: string;
            boundConn: string;
            chatProcess: string;
            created: string;
            lastActivity: string;
            lastLabel: string;
            processesTitle: string;
            noProcesses: string;
        };
        process: {
            titlePrefix: string;
            mongoId: string;
            session: string;
            engine: string;
            recipe: string;
            status: string;
            parent: string;
            goal: string;
            created: string;
            updated: string;
            engineParams: string;
            activeSkills: string;
            noneActive: string;
            fromRecipe: string;
            oneShot: string;
            pendingQueue: string;
            drained: string;
            chatLoading: string;
            chatEmptyHeadline: string;
            chatEmptyBody: string;
            archivedToMemory: string;
            memoryLoading: string;
            memoryEmptyHeadline: string;
            memoryEmptyBody: string;
            supersededBy: string;
            sources: string;
            metadata: string;
            treeLoading: string;
            treeEmptyHeadline: string;
            treeEmptyBody: string;
            marvinTreeTitle: string;
        };
        help: {
            title: string;
            loading: string;
            unavailable: string;
            empty: string;
        };
        llmTrace: {
            loading: string;
            emptyHeadline: string;
            emptyBody: string;
            orphan: string;
            toolCall: string;
            toolResult: string;
            idLabel: string;
            seqLabel: string;
            emptyLeg: string;
            legCount: string;
            toolCallSingular: string;
            toolCallPlural: string;
            tokensInOut: string;
        };
        timeline: {
            loading: string;
            noProcessesHeadline: string;
            noProcessesBody: string;
            failedToLoad: string;
            eventSpawn: string;
            eventChat: string;
            eventMemory: string;
            eventMemoryWithTitle: string;
            eventMarvinNode: string;
            eventPending: string;
            noGoal: string;
            tagRecipe: string;
            tagArchived: string;
            tagSuperseded: string;
            tagQueued: string;
        };
        processTree: {
            openInView: string;
            eventCountSingular: string;
            eventCountPlural: string;
        };
        marvin: {
            noGoal: string;
            toWorker: string;
            failure: string;
        };
    };
    tools: {
        pageTitle: string;
        loading: string;
        vanceProjectLabel: string;
        projectsGroup: string;
        vanceSystemLabel: string;
        breadcrumbProjectPrefix: string;
        sidebar: {
            addNew: string;
            noToolsHeadline: string;
            noToolsBody: string;
            disabled: string;
            primary: string;
        };
        empty: {
            headline: string;
            body: string;
        };
        detail: {
            projectLabel: string;
            lastEdit: string;
            delete: string;
            save: string;
            vanceNote: string;
        };
        cards: {
            identityTitle: string;
            parametersTitle: string;
            parametersHelp: string;
            labelsTitle: string;
        };
        fields: {
            type: string;
            description: string;
            descriptionHelp: string;
            enabled: string;
            primary: string;
            labels: string;
            labelsHelp: string;
        };
        rightPanel: {
            typeSchemaTitle: string;
            pickTypeHint: string;
            cascadeTitle: string;
            cascadeBody: string;
        };
        errors: {
            typeRequired: string;
            descriptionRequired: string;
            parametersMustBeObject: string;
            parametersInvalidJson: string;
            nameRequired: string;
            namePattern: string;
            nameAlreadyExists: string;
            createFailed: string;
        };
        banners: {
            saved: string;
            deleted: string;
            created: string;
        };
        confirmDelete: string;
        stubDescription: string;
        newModal: {
            title: string;
            nameLabel: string;
            nameHelp: string;
            stubInfo: string;
            cancel: string;
            create: string;
        };
    };
    users: {
        pageTitle: string;
        loading: string;
        none: string;
        breadcrumbs: {
            userPrefix: string;
            teamPrefix: string;
        };
        sidebar: {
            usersTitle: string;
            teamsTitle: string;
            addUser: string;
            addTeam: string;
            memberCountSingular: string;
            memberCountPlural: string;
            disabledSuffix: string;
        };
        empty: {
            headline: string;
            body: string;
        };
        user: {
            cardTitle: string;
            ownAccountNote: string;
            nameLabel: string;
            nameImmutable: string;
            titleLabel: string;
            emailLabel: string;
            statusLabel: string;
            createdLabel: string;
            delete: string;
            setPassword: string;
            save: string;
            cantDisableSelf: string;
            cantDeleteSelf: string;
            saved: string;
            deleted: string;
            confirmDelete: string;
            statusOptions: {
                active: string;
                disabled: string;
                pending: string;
            };
            settings: {
                cardTitle: string;
                intro: string;
                noSettingsHeadline: string;
                noSettingsBody: string;
                valueLabel: string;
                newPasswordLabel: string;
                passwordEmptyToClear: string;
                descriptionLabel: string;
                edit: string;
                delete: string;
                save: string;
                cancel: string;
                empty: string;
                confirmDelete: string;
                addTitle: string;
                keyLabel: string;
                keyPlaceholder: string;
                typeLabel: string;
                passwordLabel: string;
                descriptionOptional: string;
                add: string;
            };
        };
        team: {
            cardTitle: string;
            nameLabel: string;
            nameImmutable: string;
            titleLabel: string;
            enabledLabel: string;
            membersLabel: string;
            membersPlaceholder: string;
            memberHelpSingular: string;
            memberHelpPlural: string;
            createdLabel: string;
            delete: string;
            save: string;
            saved: string;
            deleted: string;
            confirmDelete: string;
        };
        helpPanel: {
            title: string;
            loading: string;
            unavailable: string;
            empty: string;
        };
        createUser: {
            title: string;
            nameLabel: string;
            nameHelp: string;
            titleLabel: string;
            emailLabel: string;
            passwordLabel: string;
            passwordHelp: string;
            cancel: string;
            create: string;
            nameInvalid: string;
            alreadyExists: string;
            createFailed: string;
            created: string;
        };
        createTeam: {
            title: string;
            nameLabel: string;
            nameHelp: string;
            titleLabel: string;
            membersLabel: string;
            membersPlaceholder: string;
            cancel: string;
            create: string;
            nameInvalid: string;
            alreadyExists: string;
            createFailed: string;
            created: string;
        };
        setPassword: {
            title: string;
            intro: string;
            newPasswordLabel: string;
            repeatPasswordLabel: string;
            cancel: string;
            submit: string;
            required: string;
            mismatch: string;
            failed: string;
            updated: string;
        };
    };
    scopes: {
        pageTitle: string;
        loading: string;
        common: {
            name: string;
            title: string;
            enabled: string;
            save: string;
            cancel: string;
            create: string;
            empty: string;
            none: string;
            noGroup: string;
            disabled: string;
            archived: string;
        };
        sidebar: {
            tenant: string;
            projectGroups: string;
            addGroup: string;
            addProject: string;
            ungroupedProjects: string;
        };
        breadcrumbs: {
            groupPrefix: string;
            projectPrefix: string;
        };
        tenant: {
            cardTitle: string;
            nameImmutable: string;
            saved: string;
        };
        group: {
            cardTitle: string;
            reservedNote: string;
            nameImmutable: string;
            delete: string;
            saved: string;
            created: string;
            deleted: string;
            confirmDelete: string;
        };
        project: {
            cardTitle: string;
            archivedNote: string;
            nameImmutable: string;
            groupLabel: string;
            archive: string;
            statusLabel: string;
            podLabel: string;
            claimedLabel: string;
            createdLabel: string;
            saved: string;
            created: string;
            archived: string;
            confirmArchive: string;
        };
        kit: {
            cardTitle: string;
            loading: string;
            none: string;
            versionPrefix: string;
            origin: string;
            path: string;
            branch: string;
            commit: string;
            installed: string;
            documents: string;
            settings: string;
            tools: string;
            inherits: string;
            install: string;
            update: string;
            apply: string;
            export: string;
            installed_msg: string;
            updated_msg: string;
            applied_msg: string;
            exported_msg: string;
            lastOperation: string;
            docsAdded: string;
            docsUpdated: string;
            docsRemoved: string;
            settingsTouched: string;
            toolsTouched: string;
            passwordsSkipped: string;
            dialog: {
                installTitle: string;
                updateTitle: string;
                applyTitle: string;
                exportTitle: string;
                repoUrl: string;
                repoUrlHelp: string;
                repoUrlReuseHelp: string;
                subPath: string;
                subPathHelp: string;
                branchLabel: string;
                branchHelp: string;
                commitSha: string;
                commitShaHelp: string;
                authToken: string;
                authTokenHelp: string;
                vaultPassword: string;
                vaultPasswordExportHelp: string;
                vaultPasswordImportHelp: string;
                commitMessage: string;
                commitMessageHelp: string;
                prune: string;
                pruneHelp: string;
                keepPasswords: string;
                keepPasswordsHelp: string;
                submitInstall: string;
                submitUpdate: string;
                submitApply: string;
                submitExport: string;
            };
        };
        settingsPanel: {
            title: string;
            noSettingsHeadline: string;
            noSettingsBody: string;
            valueLabel: string;
            newPasswordLabel: string;
            passwordEmptyToClear: string;
            descriptionLabel: string;
            edit: string;
            deleteLabel: string;
            confirmDelete: string;
            addTitle: string;
            keyLabel: string;
            keyPlaceholder: string;
            typeLabel: string;
            passwordLabel: string;
            descriptionOptional: string;
            add: string;
            types: {
                string: string;
                int: string;
                long: string;
                double: string;
                boolean: string;
                password: string;
            };
        };
        createGroup: {
            title: string;
            nameHelp: string;
        };
        createProject: {
            title: string;
            nameHelp: string;
        };
    };
    inbox: {
        pageTitle: string;
        breadcrumbInbox: string;
        breadcrumbArchive: string;
        breadcrumbTeam: string;
        sidebar: {
            inbox: string;
            archive: string;
            teamInbox: string;
            loadingTeams: string;
            noTeams: string;
        };
        list: {
            emptyHeadline: string;
            emptyBody: string;
            noTitle: string;
        };
        detail: {
            pickAnItem: string;
            pickAnItemBody: string;
            noTitle: string;
            fromLabel: string;
            toLabel: string;
            statusLabel: string;
            criticalityLabel: string;
            noBody: string;
            payload: string;
            answer: string;
            answerOutcome: string;
            answerValue: string;
            answerReason: string;
            answerBy: string;
        };
        actions: {
            yes: string;
            no: string;
            send: string;
            noOptionsHint: string;
            reasonPlaceholder: string;
            insufficientInfo: string;
            undecidable: string;
            toDocument: string;
            delegate: string;
            dismiss: string;
            archive: string;
            unarchive: string;
        };
        delegate: {
            title: string;
            body: string;
            recipient: string;
            note: string;
            cancel: string;
            confirm: string;
        };
    };
    profile: {
        pageTitle: string;
        loading: string;
        identity: {
            title: string;
            displayName: string;
            displayNamePlaceholder: string;
            email: string;
            saved: string;
        };
        preferences: {
            title: string;
            description: string;
            language: string;
            languageBrowserDefault: string;
            languageSaved: string;
        };
        teams: {
            title: string;
            empty: string;
            memberCountOne: string;
            memberCountOther: string;
            disabled: string;
            disabledTooltip: string;
        };
    };
};
export default _default;
//# sourceMappingURL=en.d.ts.map