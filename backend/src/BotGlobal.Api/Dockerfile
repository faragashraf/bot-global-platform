FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

COPY backend ./backend
COPY frontend/public/brand ./frontend/public/brand
RUN dotnet restore backend/src/BotGlobal.Api/BotGlobal.Api.csproj
RUN dotnet publish backend/src/BotGlobal.Api/BotGlobal.Api.csproj \
    --configuration Release \
    --no-restore \
    --output /app/publish

FROM mcr.microsoft.com/dotnet/aspnet:10.0 AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends libgssapi-krb5-2 \
    && rm -rf /var/lib/apt/lists/*

ENV ASPNETCORE_URLS=http://+:8080
EXPOSE 8080

COPY --from=build /app/publish .
ENTRYPOINT ["dotnet", "BotGlobal.Api.dll"]
