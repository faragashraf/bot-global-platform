using BotGlobal.Identity.Domain;
using Microsoft.AspNetCore.Identity;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace BotGlobal.Identity.Infrastructure;

public static class BootstrapAdminInitializer
{
    public static async Task InitializeBootstrapAdminAsync(
        this IServiceProvider services,
        IConfiguration configuration,
        CancellationToken cancellationToken = default)
    {
        var options =
            configuration
                .GetSection(
                    BootstrapAdminOptions.SectionName)
                .Get<BootstrapAdminOptions>()
            ?? new BootstrapAdminOptions();

        if (!options.Enabled)
        {
            return;
        }

        var password =
            Environment.GetEnvironmentVariable(
                "BOTGLOBAL_BOOTSTRAP_ADMIN_PASSWORD");

        if (string.IsNullOrWhiteSpace(password))
        {
            throw new InvalidOperationException(
                "Bootstrap admin is enabled but BOTGLOBAL_BOOTSTRAP_ADMIN_PASSWORD is not set.");
        }

        using var scope = services.CreateScope();

        var roleManager =
            scope.ServiceProvider
                .GetRequiredService<
                    RoleManager<IdentityRole<Guid>>>();

        var userManager =
            scope.ServiceProvider
                .GetRequiredService<
                    UserManager<ApplicationUser>>();

        var logger =
            scope.ServiceProvider
                .GetRequiredService<ILoggerFactory>()
                .CreateLogger("IdentityBootstrap");

        if (!await roleManager.RoleExistsAsync(
            IdentityRoles.Administrator))
        {
            EnsureSucceeded(
                await roleManager.CreateAsync(
                    new IdentityRole<Guid>(
                        IdentityRoles.Administrator)),
                "creating Administrator role");
        }

        var user =
            await userManager.FindByNameAsync(
                options.UserName)
            ?? await userManager.FindByEmailAsync(
                options.Email);

        if (user is null)
        {
            user = new ApplicationUser(
                Guid.NewGuid(),
                options.UserName,
                options.Email,
                options.DisplayName);

            EnsureSucceeded(
                await userManager.CreateAsync(
                    user,
                    password),
                "creating bootstrap administrator");
        }

        if (!user.IsActive)
        {
            user.Activate();

            EnsureSucceeded(
                await userManager.UpdateAsync(user),
                "activating bootstrap administrator");
        }

        if (!await userManager.IsInRoleAsync(
            user,
            IdentityRoles.Administrator))
        {
            EnsureSucceeded(
                await userManager.AddToRoleAsync(
                    user,
                    IdentityRoles.Administrator),
                "assigning Administrator role");
        }

        logger.LogInformation(
            "Bootstrap administrator '{UserName}' is ready.",
            user.UserName);

        cancellationToken.ThrowIfCancellationRequested();
    }

    private static void EnsureSucceeded(
        IdentityResult result,
        string operation)
    {
        if (result.Succeeded)
        {
            return;
        }

        var details =
            string.Join(
                "; ",
                result.Errors.Select(
                    error =>
                        $"{error.Code}: {error.Description}"));

        throw new InvalidOperationException(
            $"Identity bootstrap failed while {operation}: {details}");
    }
}
