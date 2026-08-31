using SentriCam.Domain.Common;

namespace SentriCam.Domain.Devices;

public readonly record struct DeviceId(Guid Value)
{
    public static DeviceId New() => new(Guid.NewGuid());

    public static DeviceId From(Guid value)
    {
        if (value == Guid.Empty)
        {
            throw new DomainValidationException("Device id cannot be empty.");
        }

        return new DeviceId(value);
    }

    public override string ToString() => Value.ToString("D");
}
