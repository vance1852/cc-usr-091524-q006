package com.admin.equipment.service.inspection;

import com.admin.equipment.model.inspection.InspectionTemplate;
import com.admin.equipment.model.inspection.InspectionTemplateItem;
import com.admin.equipment.repo.inspection.InspectionTemplateItemRepository;
import com.admin.equipment.repo.inspection.InspectionTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class InspectionTemplateService {

    private final InspectionTemplateRepository templateRepo;
    private final InspectionTemplateItemRepository itemRepo;

    public InspectionTemplateService(InspectionTemplateRepository templateRepo,
                                      InspectionTemplateItemRepository itemRepo) {
        this.templateRepo = templateRepo;
        this.itemRepo = itemRepo;
    }

    public List<InspectionTemplate> listAll() {
        return templateRepo.findAllByOrderByCodeAsc();
    }

    public List<InspectionTemplate> listByEquipmentType(String type) {
        return templateRepo.findByEquipmentTypeOrderByCodeAsc(type);
    }

    public Optional<InspectionTemplate> getById(Long id) {
        return templateRepo.findById(id);
    }

    public List<InspectionTemplateItem> getItems(Long templateId) {
        return itemRepo.findByTemplateIdOrderBySortOrderAsc(templateId);
    }

    @Transactional
    public InspectionTemplate create(String code, String name, String equipmentType, String description,
                                      List<ItemSpec> items) {
        if (code == null || code.isBlank()) throw new IllegalArgumentException("编号必填");
        if (name == null || name.isBlank()) throw new IllegalArgumentException("名称必填");
        if (templateRepo.existsByCode(code)) throw new IllegalArgumentException("编号已存在");
        InspectionTemplate t = new InspectionTemplate();
        t.setCode(code);
        t.setName(name);
        t.setEquipmentType(equipmentType == null ? "" : equipmentType);
        t.setDescription(description == null ? "" : description);
        InspectionTemplate saved = templateRepo.save(t);
        if (items != null) {
            int sort = 1;
            for (ItemSpec spec : items) {
                InspectionTemplateItem item = new InspectionTemplateItem();
                item.setTemplateId(saved.getId());
                item.setName(spec.name());
                item.setType(validType(spec.type()));
                item.setSortOrder(sort++);
                item.setNormalMin(spec.normalMin());
                item.setNormalMax(spec.normalMax());
                item.setQualifiedOptions(spec.qualifiedOptions() == null ? "" : spec.qualifiedOptions());
                item.setJudgeCriteria(spec.judgeCriteria() == null ? "" : spec.judgeCriteria());
                itemRepo.save(item);
            }
        }
        return saved;
    }

    public record ItemSpec(String name, String type, Double normalMin, Double normalMax,
                           String qualifiedOptions, String judgeCriteria) {}

    @Transactional
    public InspectionTemplate update(Long id, String name, String equipmentType, String description,
                                      List<ItemSpec> items) {
        InspectionTemplate t = templateRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("模板不存在"));
        if (name != null && !name.isBlank()) t.setName(name);
        if (equipmentType != null) t.setEquipmentType(equipmentType);
        if (description != null) t.setDescription(description);
        if (items != null) {
            itemRepo.deleteByTemplateId(id);
            int sort = 1;
            for (ItemSpec spec : items) {
                InspectionTemplateItem item = new InspectionTemplateItem();
                item.setTemplateId(id);
                item.setName(spec.name());
                item.setType(validType(spec.type()));
                item.setSortOrder(sort++);
                item.setNormalMin(spec.normalMin());
                item.setNormalMax(spec.normalMax());
                item.setQualifiedOptions(spec.qualifiedOptions() == null ? "" : spec.qualifiedOptions());
                item.setJudgeCriteria(spec.judgeCriteria() == null ? "" : spec.judgeCriteria());
                itemRepo.save(item);
            }
        }
        return templateRepo.save(t);
    }

    @Transactional
    public void delete(Long id) {
        if (!templateRepo.existsById(id)) throw new IllegalArgumentException("模板不存在");
        itemRepo.deleteByTemplateId(id);
        templateRepo.deleteById(id);
    }

    public JudgeResult judgeItem(InspectionTemplateItem item, String valueStr, Double numericValue) {
        String type = item.getType();
        if ("numeric".equals(type)) {
            if (numericValue == null && valueStr != null) {
                try { numericValue = Double.parseDouble(valueStr.trim()); }
                catch (NumberFormatException e) { return new JudgeResult(false, null, "数值格式错误"); }
            }
            if (numericValue == null) return new JudgeResult(false, null, "缺失数值");
            boolean ok = true;
            String detail = "";
            if (item.getNormalMin() != null && numericValue < item.getNormalMin()) {
                ok = false;
                detail = "低于最小值 " + item.getNormalMin();
            }
            if (item.getNormalMax() != null && numericValue > item.getNormalMax()) {
                ok = false;
                detail = detail.isEmpty() ? ("高于最大值 " + item.getNormalMax()) : (detail + " 且高于最大值 " + item.getNormalMax());
            }
            if (ok) detail = "数值在正常范围内";
            return new JudgeResult(ok, numericValue, detail);
        } else if ("option".equals(type)) {
            if (valueStr == null || valueStr.isBlank()) {
                return new JudgeResult(false, null, "缺失选项值");
            }
            String options = item.getQualifiedOptions();
            boolean ok = options == null || options.isBlank() ||
                    Arrays.asList(options.split(",")).contains(valueStr.trim());
            return new JudgeResult(ok, null, ok ? "选项合格" : ("选项 '" + valueStr + "' 不在合格范围 [" + options + "]"));
        } else {
            if (valueStr == null || valueStr.isBlank()) {
                return new JudgeResult(false, null, "缺失检查值");
            }
            boolean ok = !"异常".equals(valueStr.trim()) && !"fail".equalsIgnoreCase(valueStr.trim());
            return new JudgeResult(ok, null, ok ? "正常" : "判定异常");
        }
    }

    public record JudgeResult(boolean qualified, Double numericValue, String detail) {}

    private String validType(String t) {
        if (t == null) return "option";
        return switch (t) {
            case "numeric", "option", "text" -> t;
            default -> "option";
        };
    }
}
