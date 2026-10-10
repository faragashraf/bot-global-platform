using BotGlobal.Communication.Infrastructure.Presence;

namespace BotGlobal.UnitTests.Communication;

public sealed class PresenceSecurityTests
{
    [Theory]
    [InlineData("http://approved-project-default-rtdb.firebaseio.com/", "approved-project-default-rtdb.firebaseio.com", false, false)]
    [InlineData("https://user@approved-project-default-rtdb.firebaseio.com/", "approved-project-default-rtdb.firebaseio.com", false, false)]
    [InlineData("https://approved-project-default-rtdb.firebaseio.com/path", "approved-project-default-rtdb.firebaseio.com", false, false)]
    [InlineData("https://approved-project-default-rtdb.firebaseio.com/?query=1", "approved-project-default-rtdb.firebaseio.com", false, false)]
    [InlineData("https://other.example/", "approved-project-default-rtdb.firebaseio.com", false, false)]
    [InlineData("https://approved-project-default-rtdb.firebaseio.com:444/", "approved-project-default-rtdb.firebaseio.com", false, false)]
    [InlineData("https://approved-project-default-rtdb.firebaseio.com/", "approved-project-default-rtdb.firebaseio.com", false, true)]
    public void Production_profile_requires_the_exact_approved_https_origin(
        string url,
        string host,
        bool emulator,
        bool expected)
    {
        var profile = Profile(url, host, emulator);

        Assert.Equal(expected, profile.IsValid(out _));
    }

    [Fact]
    public void Test_emulator_requires_demo_project_loopback_and_no_credential()
    {
        var valid = WithTestValues(Profile("http://127.0.0.1:9000/", "127.0.0.1", true), "demo-presence", "");
        var realProject = WithTestValues(Profile("http://127.0.0.1:9000/", "127.0.0.1", true), "real-project", "");
        var remote = WithTestValues(Profile("http://emulator.example:9000/", "emulator.example", true), "demo-presence", "");

        Assert.True(valid.IsValid(out _));
        Assert.False(realProject.IsValid(out _));
        Assert.False(remote.IsValid(out _));
    }

    [Fact]
    public void Disabled_profile_needs_no_project_host_or_credential()
    {
        var profile = new FirebasePresenceProfile { Enabled = false };

        Assert.True(profile.IsValid(out _));
    }

    [Fact]
    public void Credential_type_project_and_endpoints_are_fail_closed()
    {
        var profile = Profile(
            "https://approved-project-default-rtdb.firebaseio.com/",
            "approved-project-default-rtdb.firebaseio.com",
            false);

        profile.CredentialJson = Credential("authorized_user", "approved-project");
        Assert.False(profile.IsValid(out _));
        profile.CredentialJson = Credential("service_account", "other-project");
        Assert.False(profile.IsValid(out _));
        profile.CredentialJson = Credential("service_account", "approved-project", "https://attacker.example/token");
        Assert.False(profile.IsValid(out _));
    }

    [Fact]
    public void Generic_application_scoped_profile_does_not_embed_nqrb_authorization()
    {
        var profile = Profile(
            "https://approved-project-default-rtdb.firebaseio.com/",
            "approved-project-default-rtdb.firebaseio.com",
            false);
        profile.ApplicationKey = "synthetic-app";

        Assert.True(profile.IsValid(out _));
    }

    private static FirebasePresenceProfile Profile(string url, string host, bool emulator) => new()
    {
        Enabled = true,
        ApplicationKey = "nqrb",
        ProjectId = emulator ? "demo-presence" : "approved-project",
        DatabaseNamespace = emulator ? "demo-presence" : "approved-project-default-rtdb",
        DatabaseUrl = url,
        AllowedDatabaseHost = host,
        CredentialJson = emulator ? "" : Credential("service_account", "approved-project"),
        Emulator = emulator,
    };
    private static FirebasePresenceProfile WithTestValues(
        FirebasePresenceProfile profile,
        string projectId,
        string credential)
    {
        profile.ProjectId = projectId;
        profile.CredentialJson = credential;
        return profile;
    }

    private static string Credential(string type, string project, string tokenUri = "https://oauth2.googleapis.com/token") =>
        $$"""{"type":"{{type}}","project_id":"{{project}}","client_email":"presence@example.test","private_key":"synthetic-private-key","token_uri":"{{tokenUri}}"}""";
}
