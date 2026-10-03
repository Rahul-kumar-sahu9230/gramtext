"""Lightweight STR model: MobileViTv2 backbone -> BiLSTM -> CTC head (~1.5M params)."""
import timm
import torch
from torch import nn

from charset import NUM_CLASSES


class GramTextSTR(nn.Module):
    def __init__(self, num_classes: int = NUM_CLASSES, pretrained: bool = False):
        super().__init__()
        self.backbone = timm.create_model(
            "mobilevitv2_050", pretrained=pretrained, features_only=True,
            out_indices=(2,), in_chans=1,
        )
        # Downsample height only in the MobileViT stage, so a 256 px wide crop keeps
        # 64 time steps - enough for long Devanagari words at code-point level.
        self.backbone.stages_2[0].conv2_kxk.conv.stride = (2, 1)
        channels = self.backbone.feature_info.channels()[-1]
        self.rnn = nn.LSTM(channels, 128, num_layers=2, bidirectional=True, batch_first=True, dropout=0.1)
        self.head = nn.Linear(256, num_classes)

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        """x: (B, 1, 32, 256) in [-1, 1] -> logits (B, T=64, num_classes)."""
        features = self.backbone(x)[-1]          # (B, C, 4, 64)
        seq = features.mean(dim=2).transpose(1, 2)  # (B, 64, C)
        seq, _ = self.rnn(seq)
        return self.head(seq)
