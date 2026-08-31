using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Metadata.Builders;
using SentriCam.Domain.Hierarchy;

namespace SentriCam.Infrastructure.Persistence.Configurations;

internal sealed class OrganizationConfiguration : IEntityTypeConfiguration<Organization>
{
    public void Configure(EntityTypeBuilder<Organization> builder)
    {
        builder.ToTable("Organizations");
        builder.HasKey(organization => organization.Id);
        builder.Property(organization => organization.Id)
            .HasOrganizationIdConversion()
            .ValueGeneratedNever();
        builder.Property(organization => organization.Name).HasMaxLength(200).IsRequired();
        builder.Property(organization => organization.CreatedAtUtc).HasPrecision(7).IsRequired();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasIndex(organization => organization.Name)
            .IsUnique()
            .HasDatabaseName("UX_Organizations_Name");
    }
}

internal sealed class SiteConfiguration : IEntityTypeConfiguration<Site>
{
    public void Configure(EntityTypeBuilder<Site> builder)
    {
        builder.ToTable("Sites");
        builder.HasKey(site => site.Id);
        builder.Property(site => site.Id).HasSiteIdConversion().ValueGeneratedNever();
        builder.Property(site => site.OrganizationId).HasOrganizationIdConversion().IsRequired();
        builder.Property(site => site.Name).HasMaxLength(200).IsRequired();
        builder.Property(site => site.CreatedAtUtc).HasPrecision(7).IsRequired();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasOne<Organization>()
            .WithMany()
            .HasForeignKey(site => site.OrganizationId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(site => new { site.OrganizationId, site.Name })
            .IsUnique()
            .HasDatabaseName("UX_Sites_OrganizationId_Name");
    }
}

internal sealed class AreaConfiguration : IEntityTypeConfiguration<Area>
{
    public void Configure(EntityTypeBuilder<Area> builder)
    {
        builder.ToTable("Areas");
        builder.HasKey(area => area.Id);
        builder.Property(area => area.Id).HasAreaIdConversion().ValueGeneratedNever();
        builder.Property(area => area.SiteId).HasSiteIdConversion().IsRequired();
        builder.Property(area => area.Name).HasMaxLength(200).IsRequired();
        builder.Property(area => area.CreatedAtUtc).HasPrecision(7).IsRequired();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasOne<Site>()
            .WithMany()
            .HasForeignKey(area => area.SiteId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(area => new { area.SiteId, area.Name })
            .IsUnique()
            .HasDatabaseName("UX_Areas_SiteId_Name");
    }
}

internal sealed class DeviceGroupConfiguration : IEntityTypeConfiguration<DeviceGroup>
{
    public void Configure(EntityTypeBuilder<DeviceGroup> builder)
    {
        builder.ToTable("DeviceGroups");
        builder.HasKey(group => group.Id);
        builder.Property(group => group.Id).HasDeviceGroupIdConversion().ValueGeneratedNever();
        builder.Property(group => group.AreaId).HasAreaIdConversion().IsRequired();
        builder.Property(group => group.Name).HasMaxLength(200).IsRequired();
        builder.Property(group => group.CreatedAtUtc).HasPrecision(7).IsRequired();
        builder.Property<byte[]>("RowVersion").IsRowVersionToken();
        builder.HasOne<Area>()
            .WithMany()
            .HasForeignKey(group => group.AreaId)
            .OnDelete(DeleteBehavior.Cascade);
        builder.HasIndex(group => new { group.AreaId, group.Name })
            .IsUnique()
            .HasDatabaseName("UX_DeviceGroups_AreaId_Name");
    }
}
