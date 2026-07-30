import Foundation
import Social
import UIKit
import UniformTypeIdentifiers

/// iOS Share-Extension entry point. Hosted by `SLComposeServiceViewController`
/// for the standard system-chrome Compose-style UI (textarea + tappable
/// configuration rows). Read accounts / projects / credentials out of the
/// App-Group container that the main `vance-facelift` app keeps refreshed,
/// then POSTs the shared text/URL to `POST /brain/{tenant}/share/inbox`.
///
/// The App-Group identifier MUST match the one declared on the wrapper's
/// App.entitlements — currently `group.de.mhus.vance.facelift`. Update
/// both places together if you ever rename it.
class ShareViewController: SLComposeServiceViewController {

    private static let appGroupId = "group.de.mhus.vance.facelift"

    private var accounts: [Account] = []
    private var selectedAccount: Account?

    private var projects: [Project] = []
    private var selectedProject: Project?

    /// Raw text payload extracted from the share item — Safari sends
    /// the page URL here as a string; Mail/Notes send the highlighted
    /// text. We forward it as the inbox-item body.
    private var sharedText: String = ""
    /// Optional URL — Safari attaches the active tab URL separately.
    private var sharedUrl: String?

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        title = "Vance"
        placeholder = "Add a note (optional)"
        loadAccounts()
        extractSharedContent()
        validateContent()
    }

    override func isContentValid() -> Bool {
        return selectedAccount != nil && selectedProject != nil
    }

    override func didSelectPost() {
        guard let account = selectedAccount,
              let project = selectedProject else {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        postShare(account: account, project: project)
    }

    override func configurationItems() -> [Any]! {
        var items: [SLComposeSheetConfigurationItem] = []

        let accountItem = SLComposeSheetConfigurationItem()!
        accountItem.title = "Account"
        accountItem.value = selectedAccount?.displayName ?? "Choose"
        accountItem.tapHandler = { [weak self] in self?.showAccountPicker() }
        items.append(accountItem)

        let projectItem = SLComposeSheetConfigurationItem()!
        projectItem.title = "Project"
        if selectedAccount == nil {
            projectItem.value = "(pick account first)"
        } else {
            projectItem.value = selectedProject.flatMap { $0.title ?? $0.name } ?? "Choose"
        }
        projectItem.tapHandler = { [weak self] in self?.showProjectPicker() }
        items.append(projectItem)

        return items
    }

    // MARK: - App-Group reads

    private func appGroupContainerURL() -> URL? {
        return FileManager.default.containerURL(
            forSecurityApplicationGroupIdentifier: ShareViewController.appGroupId)
    }

    private func loadAccounts() {
        guard let container = appGroupContainerURL() else { return }
        let file = container.appendingPathComponent("accounts.json")
        guard let data = try? Data(contentsOf: file),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else { return }
        self.accounts = raw.compactMap { dict in
            guard let id = dict["id"] as? String,
                  let brainUrl = dict["brainUrl"] as? String,
                  let displayName = dict["displayName"] as? String
            else { return nil }
            return Account(id: id, brainUrl: brainUrl, displayName: displayName)
        }
        // Auto-select if there's only one — saves a tap.
        if accounts.count == 1 {
            selectedAccount = accounts[0]
            loadProjects(for: accounts[0].id)
            if projects.count == 1 { selectedProject = projects[0] }
        }
    }

    private func loadProjects(for accountId: String) {
        guard let container = appGroupContainerURL() else { return }
        let safeId = accountId.replacingOccurrences(of: "/", with: "_")
        let file = container.appendingPathComponent("projects-\(safeId).json")
        guard let data = try? Data(contentsOf: file),
              let raw = try? JSONSerialization.jsonObject(with: data) as? [[String: Any]]
        else {
            self.projects = []
            return
        }
        self.projects = raw.compactMap { dict in
            guard let name = dict["name"] as? String else { return nil }
            return Project(name: name, title: dict["title"] as? String)
        }
    }

    private func loadCredentials(for accountId: String) -> Credentials? {
        guard let container = appGroupContainerURL() else { return nil }
        let file = container.appendingPathComponent("credentials.json")
        guard let data = try? Data(contentsOf: file),
              let all = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let dict = all[accountId] as? [String: Any]
        else { return nil }
        guard let brainUrl = dict["brainUrl"] as? String,
              let tenant = dict["tenant"] as? String,
              let token = dict["token"] as? String
        else { return nil }
        return Credentials(brainUrl: brainUrl, tenant: tenant, token: token)
    }

    // MARK: - Share-content extraction

    private func extractSharedContent() {
        guard let item = extensionContext?.inputItems.first as? NSExtensionItem,
              let attachments = item.attachments else { return }
        for attachment in attachments {
            if attachment.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
                attachment.loadItem(forTypeIdentifier: UTType.url.identifier, options: nil) {
                    [weak self] data, _ in
                    DispatchQueue.main.async {
                        if let url = data as? URL {
                            self?.sharedUrl = url.absoluteString
                        } else if let s = data as? String {
                            self?.sharedUrl = s
                        }
                    }
                }
            } else if attachment.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
                attachment.loadItem(forTypeIdentifier: UTType.plainText.identifier, options: nil) {
                    [weak self] data, _ in
                    DispatchQueue.main.async {
                        if let s = data as? String { self?.sharedText = s }
                    }
                }
            }
        }
    }

    // MARK: - Pickers

    private func showAccountPicker() {
        let picker = ShareListPickerViewController(
            title: "Account",
            items: accounts.map { $0.displayName })
        picker.onSelect = { [weak self] idx in
            guard let self = self else { return }
            self.selectedAccount = self.accounts[idx]
            self.selectedProject = nil
            self.loadProjects(for: self.accounts[idx].id)
            if self.projects.count == 1 { self.selectedProject = self.projects[0] }
            self.reloadConfigurationItems()
            self.validateContent()
        }
        pushConfigurationViewController(picker)
    }

    private func showProjectPicker() {
        guard !projects.isEmpty else { return }
        let labels = projects.map { $0.title ?? $0.name }
        let picker = ShareListPickerViewController(title: "Project", items: labels)
        picker.onSelect = { [weak self] idx in
            guard let self = self else { return }
            self.selectedProject = self.projects[idx]
            self.reloadConfigurationItems()
            self.validateContent()
        }
        pushConfigurationViewController(picker)
    }

    // MARK: - POST

    private func postShare(account: Account, project: Project) {
        guard let creds = loadCredentials(for: account.id) else {
            NSLog("[VanceShare] no credentials for account \(account.id) — abort")
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }
        let urlString = "\(creds.brainUrl)/brain/\(creds.tenant)/share/inbox"
        guard let url = URL(string: urlString) else {
            extensionContext?.completeRequest(returningItems: nil, completionHandler: nil)
            return
        }

        let title = (contentText ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        var body: [String: Any] = ["projectName": project.name]
        if !title.isEmpty { body["title"] = title }
        if !sharedText.isEmpty { body["body"] = sharedText }
        if let shared = sharedUrl, !shared.isEmpty { body["sharedUrl"] = shared }

        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(creds.token)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try? JSONSerialization.data(withJSONObject: body)

        URLSession.shared.dataTask(with: request) { [weak self] _, response, error in
            if let error = error {
                NSLog("[VanceShare] POST failed: \(error.localizedDescription)")
            } else if let http = response as? HTTPURLResponse {
                NSLog("[VanceShare] POST returned \(http.statusCode)")
            }
            DispatchQueue.main.async {
                self?.extensionContext?.completeRequest(
                    returningItems: nil, completionHandler: nil)
            }
        }.resume()
    }
}

// MARK: - Models

private struct Account {
    let id: String
    let brainUrl: String
    let displayName: String
}

private struct Project {
    let name: String
    let title: String?
}

private struct Credentials {
    let brainUrl: String
    let tenant: String
    let token: String
}

// MARK: - List picker pushed onto the compose-sheet's navigation stack

private class ShareListPickerViewController: UITableViewController {
    private let items: [String]
    var onSelect: ((Int) -> Void)?

    init(title: String, items: [String]) {
        self.items = items
        super.init(style: .plain)
        self.title = title
    }

    required init?(coder: NSCoder) { fatalError("not used") }

    override func tableView(_ tableView: UITableView, numberOfRowsInSection section: Int) -> Int {
        return items.count
    }

    override func tableView(_ tableView: UITableView, cellForRowAt indexPath: IndexPath) -> UITableViewCell {
        let cell = UITableViewCell(style: .default, reuseIdentifier: nil)
        cell.textLabel?.text = items[indexPath.row]
        return cell
    }

    override func tableView(_ tableView: UITableView, didSelectRowAt indexPath: IndexPath) {
        onSelect?(indexPath.row)
        navigationController?.popViewController(animated: true)
    }
}
