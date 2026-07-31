# Core Devices Pebble SDK for EZ_SOS (build + QEMU emulator)
FROM debian:bookworm-slim

ENV DEBIAN_FRONTEND=noninteractive \
    LANG=C.UTF-8 \
    PATH="/home/pebble/.local/bin:/home/pebble/.local/share/pebble-sdk/SDKs/current/toolchain/arm-none-eabi/bin:${PATH}"

# SDK runtime + emulator (SDL2) deps per developer.repebble.com/sdk/
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    curl \
    git \
    make \
    python3 \
    python3-pip \
    python3-venv \
    nodejs \
    npm \
    libsdl2-2.0-0 \
    libglib2.0-0 \
    libpixman-1-0 \
    zlib1g \
    libsndio7.0 \
    libfdt1 \
    libfreetype6 \
    libpulse0 \
    sudo \
    && rm -rf /var/lib/apt/lists/*

RUN useradd -m -d /home/pebble -s /bin/bash pebble \
    && echo "pebble ALL=(root) NOPASSWD:ALL" > /etc/sudoers.d/pebble \
    && chmod 0440 /etc/sudoers.d/pebble

USER pebble
WORKDIR /home/pebble

# Install uv, pebble-tool, and SDK in one layer (SDK under ~/.local/share/pebble-sdk)
RUN curl -LsSf https://astral.sh/uv/install.sh | sh \
    && export PATH="/home/pebble/.local/bin:${PATH}" \
    && uv tool install pebble-tool --python 3.13 \
    && pebble sdk install latest \
    && test -d /home/pebble/.local/share/pebble-sdk/SDKs/current

USER root
RUN chmod -R a+rwX /home/pebble/.local/share/pebble-sdk \
    && chmod -R a+rX /home/pebble/.local

COPY entrypoint.sh /usr/local/bin/pebble-entrypoint.sh
RUN chmod +x /usr/local/bin/pebble-entrypoint.sh

USER pebble
WORKDIR /workspaces/EZ_SOS

ENTRYPOINT ["/usr/local/bin/pebble-entrypoint.sh"]
CMD ["sleep", "infinity"]
